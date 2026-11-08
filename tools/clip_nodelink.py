"""표준노드링크(MOCT_LINK) SHP에서 한 지점 주변 도로만 잘라 GeoJSON(WGS84)으로 저장.
외부 라이브러리 없이 SHP/DBF를 직접 읽고, TM(중부원점 200000/600000, GRS80) 변환을 직접 계산한다.
사용: python3 clip_nodelink.py <MOCT_LINK 경로(확장자 없이)> <위도> <경도> <반경m> <출력.geojson>
"""
import json, math, struct, sys

A = 6378137.0
F = 1 / 298.257222101
E2 = F * (2 - F)
EP2 = E2 / (1 - E2)
LAT0, LON0, K0, FE, FN = math.radians(38.0), math.radians(127.0), 1.0, 200000.0, 600000.0


def _m(phi):
    e4, e6 = E2 * E2, E2 ** 3
    return A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * math.sin(4 * phi)
                - (35 * e6 / 3072) * math.sin(6 * phi))


M0 = _m(LAT0)


def fwd(lat, lon):
    phi, lam = math.radians(lat), math.radians(lon)
    n = A / math.sqrt(1 - E2 * math.sin(phi) ** 2)
    t = math.tan(phi) ** 2
    c = EP2 * math.cos(phi) ** 2
    a = (lam - LON0) * math.cos(phi)
    x = FE + K0 * n * (a + (1 - t + c) * a ** 3 / 6 + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a ** 5 / 120)
    y = FN + K0 * (_m(phi) - M0 + n * math.tan(phi) * (a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a ** 4 / 24
                                                        + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a ** 6 / 720))
    return x, y


def inv(x, y):
    m = M0 + (y - FN) / K0
    mu = m / (A * (1 - E2 / 4 - 3 * E2 ** 2 / 64 - 5 * E2 ** 3 / 256))
    e1 = (1 - math.sqrt(1 - E2)) / (1 + math.sqrt(1 - E2))
    phi1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu) + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu) + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    n1 = A / math.sqrt(1 - E2 * math.sin(phi1) ** 2)
    t1 = math.tan(phi1) ** 2
    c1 = EP2 * math.cos(phi1) ** 2
    r1 = A * (1 - E2) / (1 - E2 * math.sin(phi1) ** 2) ** 1.5
    d = (x - FE) / (n1 * K0)
    lat = phi1 - (n1 * math.tan(phi1) / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d ** 4 / 24
                                             + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d ** 6 / 720)
    lon = LON0 + (d - (1 + 2 * t1 + c1) * d ** 3 / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d ** 5 / 120) / math.cos(phi1)
    return math.degrees(lat), math.degrees(lon)


def read_dbf(path, wanted):
    with open(path, 'rb') as f:
        hdr = f.read(32)
        nrec, hlen, rlen = struct.unpack('<IHH', hdr[4:12])
        fields = []
        while True:
            b = f.read(32)
            if b[0] == 0x0D:
                break
            fields.append((b[:11].split(b'\0')[0].decode('ascii'), b[16]))
        out = {}
        for i in sorted(wanted):
            f.seek(hlen + i * rlen + 1)
            raw = f.read(rlen - 1)
            rec, pos = {}, 0
            for name, ln in fields:
                rec[name] = raw[pos:pos + ln].decode('cp949', 'replace').strip()
                pos += ln
            out[i] = rec
        return out


def main():
    base, lat, lon, radius, outp = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
    cx, cy = fwd(lat, lon)
    bx0, by0, bx1, by1 = cx - radius, cy - radius, cx + radius, cy + radius
    with open(base + '.shx', 'rb') as f:
        f.seek(100)
        idx = []
        while True:
            b = f.read(8)
            if len(b) < 8:
                break
            off, ln = struct.unpack('>ii', b)
            idx.append(off * 2)
    keep = {}
    with open(base + '.shp', 'rb') as f:
        for i, off in enumerate(idx):
            f.seek(off + 8)
            st, x0, y0, x1, y1 = struct.unpack('<i4d', f.read(36))
            if st == 0 or x1 < bx0 or x0 > bx1 or y1 < by0 or y0 > by1:
                continue
            nparts, npts = struct.unpack('<2i', f.read(8))
            parts = struct.unpack('<%di' % nparts, f.read(4 * nparts))
            pts = struct.unpack('<%dd' % (2 * npts), f.read(16 * npts))
            ll = [inv(pts[2 * k], pts[2 * k + 1]) for k in range(npts)]
            keep[i] = [[round(p[1], 7), round(p[0], 7)] for p in ll]
    attrs = read_dbf(base + '.dbf', keep.keys())
    feats = [{"type": "Feature", "properties": attrs[i], "geometry": {"type": "LineString", "coordinates": keep[i]}} for i in keep]
    with open(outp, 'w', encoding='utf-8') as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f, ensure_ascii=False)
    print('links', len(feats))
    if feats:
        print('fields', list(feats[0]['properties'].keys()))


if __name__ == '__main__':
    main()
