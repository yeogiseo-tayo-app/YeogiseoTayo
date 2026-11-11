# 여기서 타요

내 차로 가족·지인을 태우러 갈 때, 정차해도 길이 막히지 않고 타는 사람에게서 멀지 않은 **정확한 한 점**을 정해
순정 내비(경유지)와 타는 사람(링크)에게 함께 알려 주는 Pleos 차량용 앱.

- 차 앱: 만남 장소 결정 엔진 → 순정 내비 **경유지** → 음성 안내 → "지금 나오세요" 자동 → 도착·출발 판단
- 타는 사람: 앱 설치 없이 **웹 링크** — 운전자가 차 화면 QR을 자기 휴대폰으로 찍어 카카오톡·문자로 보냄. 만남 장소·걷는 길·남은 시간, 사정 버튼 4개, "지금 출발해요"
- 시연 지역: 서울 노량진역 (공공데이터 + 현장 확인 레이어)
- 시연 영상: https://youtu.be/ERgDmg60P9k (Pleos Connect 에뮬레이터 + 실제 휴대폰, 약 2분 40초)

## 폴더
| 경로 | 내용 |
| --- | --- |
| `app/` | 차 앱 (Kotlin · Compose). `engine/` 판단 엔진, `trip/` 여정 제어, `navi/` `voice/` `location/` Pleos SDK, `rider/` 웹 링크 연결 |
| `app/src/main/assets/map_noryangjin.txt` | 노량진역 반경 1.3km 도로·횡단보도·정류장 + 현장 확인(X 불가 · O 가능 · P 도로 밖 타는 곳) |
| `rider-web/` | 타는 사람 웹 (Firebase Hosting) — `index.html` 타는 사람 화면, `share.html` 링크 보내기 화면 |
| `apk/` | 심사용 release APK |
| `test-routes/` | 에뮬레이터 시연 경로(GPX) |
| `tools/` | 공공데이터 → 앱 지도 변환, 현장 확인 기록 `field_checks.txt` |

## 실행 방법
1. Android Studio 로 이 폴더를 연다 (Pleos 저장소는 `settings.gradle.kts` 에 설정됨)
2. `local.properties` 에 API 키를 적는다 (Git 에 올라가지 않음)
   ```
   TMAP_APP_KEY=...
   KAKAO_REST_KEY=...
   ```
3. Pleos Connect 에뮬레이터에서 실행 → 위치 권한 허용
4. 휴대폰으로 차 화면의 QR 을 찍으면 "링크 보내기" 화면이 열린다 → 카카오톡·문자로 보내거나, 시연에서는 "이 휴대폰에서 타는 사람 화면 열기" (시연 모드: 위치를 고정 장소로 설정)
5. 에뮬레이터 Extended controls → Location → Routes 에서 GPX 를 불러와 재생
   - 출발 약 20초 뒤 25초 신호 대기 → 이때 "만남 장소 정하기" (주행 중에는 버튼이 숨겨짐)

| 장면 | GPX | 휴대폰 시연 위치 | 보여 주는 것 |
| --- | --- | --- | --- |
| A | `noryangjin_station_parking.gpx` | 노량진역 1번 출구 | 역 앞 길가가 모두 정차 불가(로드뷰 확인) → 바로 옆 노량진 공영주차장 안에서 태우기: 경유지 → 지금 나오세요 → 도착 → 출발 |
| B | `noryangjin_station_parking.gpx` | 노량진역 1번 출구 | 사정 버튼: 장면 A 도중 "걷기 힘들어요" → 다시 판단해도 공영주차장이 최선 → "사정을 반영했어요. 지금 장소가 그대로 가장 좋아요" |
| C | `noryangjin_cheongsa_parking.gpx` | 노량진 청사(AI디지털배움터) | 길가가 모두 금지일 때 도로 밖(주차장 현관 앞)에서 태우기 |

## APK
- 위치: `apk/앵두나무_여기서타요_release.apk` (release 서명 빌드)
- 설치: Pleos Connect 에뮬레이터를 켠 뒤 `adb install apk/앵두나무_여기서타요_release.apk` → 앱 목록에서 **여기서 타요** 실행 → 위치·차량 정보 권한 허용
- 타는 사람 웹은 https://yeogiseo-tayo.web.app 에 올라가 있어 따로 설치할 것이 없다

## 타는 사람 웹 올리기
```
cd rider-web
copy public\config.example.js public\config.js   # Firebase 설정·카카오 JavaScript 키 입력 (Git 제외)
npx firebase-tools deploy --only hosting
```

## 테스트 환경
- Pleos Connect 에뮬레이터 v2.1.0 (Android Automotive 14)
- Pleos SDK: NaviHelper 3.0.5 · FusedLocation 1.1.3 · TextToSpeech 3.3.1 · Vehicle 2.3.2
- 외부: Firebase Realtime Database·Hosting, TMAP 보행자 경로, 카카오 로컬·지도
- 단위 테스트: `app/src/test` (판단 엔진·여정 제어, 안드로이드 없이 실행)
