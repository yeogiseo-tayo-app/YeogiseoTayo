# 여기서 타요

내 차로 가족·지인을 태우러 갈 때, 정차해도 길이 막히지 않고 타는 사람에게서 멀지 않은 **정확한 한 점**을 정해
순정 내비(경유지)와 타는 사람(링크)에게 함께 알려 주는 Pleos 차량용 앱.

## 현재 단계 (11/6)
- 경유지 시험 화면: 순정 내비에 목적지·경유지를 넣고 NaviHelper 이벤트를 화면에 기록

## 실행 방법
1. Android Studio 로 이 폴더를 연다 (Pleos 저장소는 `settings.gradle.kts` 에 설정됨)
2. `local.properties` 에 API 키를 적는다 (Git 에 올라가지 않음)
   ```
   TMAP_APP_KEY=...
   KAKAO_REST_KEY=...
   ```
3. Pleos Connect 에뮬레이터에서 실행

## 테스트 환경
- Pleos Connect 에뮬레이터 v2.1.0 (Android Automotive 14)
- Pleos SDK: NaviHelper 3.0.5 · FusedLocation 1.1.3 · TextToSpeech 3.3.1 · Vehicle 2.3.2
