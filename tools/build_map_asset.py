"""잘라 둔 노드링크·횡단보도·버스정류장을 앱이 읽는 단순 텍스트 형식으로 합친다.
형식(한 줄 = 한 항목, 구분자 |):
  N|nodeId|type|lat|lon|name
  L|linkId|fromNode|toNode|lanes|roadRank|roadType|maxSpd|connect|roadUse|roadName|lat,lon;lat,lon;...
  C|lat|lon|lanes|signal(Y/N)
  B|id|lat|lon|name
  X|lat1|lon1|lat2|lon2|reason|checkedOn   (tools/field_checks.txt 현장 확인 정차 불가 구간)
  O|… 정차 가능 구간,  P|이름|입구위도|입구경도|타는곳위도|타는곳경도|메모|날짜|Y/N  도로 밖 타는 곳
사용: python3 build_map_asset.py <작업폴더> <원본 다운로드 폴더> <중심위도> <중심경도> <반경m> <출력.txt>
"""
import csv, io, json, math, sys

work, src, la0, lo0, radius, outp = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5]), sys.argv[6]


def near(lat, lon):
    return math.hypot((lat - la0) * 111000, (lon - lo0) * 111000 * math.cos(math.radians(la0))) < radius


def clean(s):
    return (s or '').replace('|', '/').replace('\n', ' ').strip()


lines = ['# 여기서 타요 지도 데이터 · 중심 %.5f,%.5f 반경 %dm · 출처: 국가교통정보센터 표준노드링크(2026-10-14), '
         '행정안전부 전국횡단보도표준데이터, 국토교통부 전국 버스정류장 위치정보(2026-10-20)' % (la0, lo0, radius)]
for n in json.load(open(f'{work}/data_noryangjin_nodes.json', encoding='utf-8')):
    lines.append('N|%s|%s|%.7f|%.7f|%s' % (n['NODE_ID'], n['NODE_TYPE'], n['lat'], n['lon'], clean(n['NODE_NAME'])))
for f in json.load(open(f'{work}/data_noryangjin_links.geojson', encoding='utf-8'))['features']:
    p = f['properties']
    coords = ';'.join('%.7f,%.7f' % (c[1], c[0]) for c in f['geometry']['coordinates'])
    lines.append('L|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s' % (p['LINK_ID'], p['F_NODE'], p['T_NODE'], p['LANES'], p['ROAD_RANK'], p['ROAD_TYPE'],
                                                       p['MAX_SPD'], p['CONNECT'], p['ROAD_USE'], clean(p['ROAD_NAME']), coords))
cw = csv.DictReader(io.StringIO(open(f'{src}/전국횡단보도표준데이터.csv', 'rb').read().decode('cp949')))
nc = 0
for x in cw:
    if x['위도'].strip() and near(float(x['위도']), float(x['경도'])):
        lines.append('C|%s|%s|%s|%s' % (x['위도'].strip(), x['경도'].strip(), x['차로수'].strip() or '0', x['보행자신호등유무'].strip() or 'N'))
        nc += 1
bs = csv.DictReader(io.StringIO(open(f'{src}/국토교통부_전국 버스정류장 위치정보_20261020.csv', 'rb').read().decode('cp949')))
nb = 0
for x in bs:
    if x['위도'].strip() and near(float(x['위도']), float(x['경도'])):
        lines.append('B|%s|%s|%s|%s' % (x['정류장번호'], x['위도'], x['경도'], clean(x['정류장명'])))
        nb += 1
import os
fc = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'field_checks.txt')
if os.path.exists(fc):
    lines += [l.rstrip('\n') for l in open(fc, encoding='utf-8') if l[:2] in ('X|', 'O|', 'P|')]
open(outp, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print('nodes/links/crosswalks/busstops', sum(l[0] == 'N' for l in lines), sum(l[0] == 'L' for l in lines), nc, nb)
