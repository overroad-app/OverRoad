# OverRoad (오버로드)

> 도로 위(Over the Road)에서 모든 차를 내려다보며 잇는 차량 간 연결망

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. Pleos Connect SDK로 만든 차량 앱과 중계 서버로 이루어져 있습니다.

도로 위 차량들이 서로의 눈이 됩니다. 한 대가 겪은 급제동, 사고, 악천후, 충전 대기 상황을 1초 안에, 그 상황을 실제로 만날 뒤차에만 알려 줍니다. 국내 도로에서 가장 많이 달리는 현대차그룹 차량을 하나의 감지망으로 잇는 **점유율 기반 차량 간 연결망**이 이 앱의 본체이고, 아래 기능들은 그 위에 얹는 응용입니다.

## 주요 기능

| 기능 | 내용 |
|---|---|
| 급제동 경고 | 감속도와 브레이크 입력으로 급제동을 판정하고, 같은 차로에서 10초 안에 닿을 뒤차에만 경고. 브레이크부터 뒤차 화면까지 평균 0.64초(실측) |
| 2차 사고 방지 | 에어백·비상등·도로 위 정차로 사고·정차 차량을 판단하고, 사고 정체가 생기면 정체 꼬리에서 한 번 더 경고 |
| 기상·노면 위험 | 와이퍼·안개등·ABS·ESC가 한 지역에서 동시에 많이 켜질 때 확정, 외부 제공 피드(JSON·GeoJSON) |
| EV 충전 예측 | 충전소의 지금 상태와 오는 차량으로 내 순번·대기·완료 시각 예측, 충전 기록과 별점 축적 |
| 긴급차량 길 터주기 | 구급·소방·경찰차 접근을 앞쪽 1km 차량에 차로까지 안내 |
| 차로 변경 의도 공유 | 깜빡이를 켜면 옆 차로에서 다가오는 차에만 알림, 진입 성공 시 자동 감사 인사 |
| 소리 이모트 | 비상등 인사를 대신하는 👍·🙏·👋. 받을 앞차·뒤차를 점선으로 미리 보여 주고 그 선을 따라 전송. 5초 2번·같은 차 3분 제한, "항상 감정표현 받지 않기" |
| OverRoad 기본 화면 | 참여 차량이 보내는 실제 위치·상태로 그린 주변 차량 인식 화면. 참여 차 센서가 본 비연동 차량 표시 경로(Tier 2) |

운전 중 주의 분산을 먼저 고려했습니다. 주행 중에는 내비가 앞에 있고, 경고는 OS 상단 알림과 같은 흰 카드와 음성으로만 전합니다. 화면 조작은 정차·저속에서만 됩니다.

## 저장소 구성

```
app/       차량 앱 (Kotlin, Pleos Connect SDK: Vehicle · NaviHelper · TextToSpeech)
server/    중계 서버 (Python 표준 라이브러리만 사용) + 실시간 대시보드
scripts/   가상 차량 시뮬레이터(fleet.py), 기능별 시연 시나리오, 지연시간 측정, 녹화 도구
docs/      통합보고서(PDF), 지향점·Tier별 기능, 기술 리포트(판별 로직), 개선 기록, 지연시간 실측
apk/       release APK
```

자세한 설명은 [`docs/OverRoad_통합보고서.pdf`](docs/OverRoad_통합보고서.pdf)에 있습니다. 판별 로직은 [`docs/technical_report.md`](docs/technical_report.md), 단계별 로드맵은 [`docs/philosophy.md`](docs/philosophy.md)를 보세요.

## APK

- 위치: [`apk/OverRoad_release.apk`](apk/OverRoad_release.apk) (release 빌드, 서명됨)
- 패키지: `com.hyperconnect.app` · 버전 0.1.0 · minSdk 28 · targetSdk 34

## 테스트 환경 및 필요한 설정

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android 14, API 34), 시연은 2대(B·C) 동시 실행 |
| 개발 도구 | Android Studio (내장 JDK 사용), Gradle Wrapper 포함, Kotlin |
| Pleos SDK | Vehicle 2.3.2 · NaviHelper 3.0.5 · TextToSpeech 3.3.1 |
| 서버 | Python 3 (추가 패키지 없음), 호스트 PC의 8080 포트 |
| 권한 | `android.car.permission.CAR_SPEED`(속도), `POST_NOTIFICATIONS`(경고 알림) — 아래 실행 방법의 `pm grant`로 허용 |
| 첫 실행 | 데이터 공유 동의 화면에서 동의해야 서버 연결을 시작합니다(`--ez consent true`로 건너뛸 수 있음) |
| 확인한 성능 | 주행 중 CPU 약 1~1.5%, 메모리 약 65MB (Pleos 기준 5% 미만 · 400MB 이하) |

## 실행 방법

필요한 것: Android Studio(JDK 포함), Pleos Connect Emulator(Android 14, API 34), Python 3.

```bash
# 1) 중계 서버 (대시보드: http://127.0.0.1:8080/dashboard)
python3 -I server/server.py

# 2) 에뮬레이터를 켠 뒤 앱 설치 (release APK 또는 직접 빌드)
adb install -r apk/OverRoad_release.apk
# 직접 빌드할 때는 Android Studio의 JDK를 쓴다
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug

# 3) 차량 속도 권한과 알림 권한
adb shell pm grant --user 10 com.hyperconnect.app android.car.permission.CAR_SPEED
adb shell pm grant --user 10 com.hyperconnect.app android.permission.POST_NOTIFICATIONS

# 4) 실행 (vehicle_id: 서버에서 쓰는 가명 ID, lane: 차로 번호)
adb shell am start -n com.hyperconnect.app/.MainActivity --es vehicle_id B --ei lane 2
```

앱은 에뮬레이터 안에서 `http://10.0.2.2:8080`(호스트 PC의 서버)에 접속합니다. 다른 서버를 쓰려면 실행할 때 `--es server http://주소:포트`를 붙입니다.

에뮬레이터 2대와 가상 차량으로 여러 대의 도로 상황을 만들 수 있습니다.

```bash
bash scripts/scenarios/launch_bc.sh                              # 에뮬레이터 B, C 실행
python3 scripts/fleet.py run --anchor B A:2:+250 D:1:-120       # 가상 차량 추가
bash scripts/hard_brake.sh                                       # B 급제동
```

기능별 시연 시나리오는 `scripts/scenarios/scenario_*.sh`에 있습니다. 각 파일 머리말에 무엇을 보여 주는지 적어 두었습니다.

## 지연시간 실측 (급제동 경고, 에뮬레이터 10회)

| 구간 | 평균 | 최대 |
|---|---|---|
| 브레이크 → 급제동 판정 | 631 ms | 755 ms |
| 판정 → 서버 → 뒤차 화면 | 6 ms | 7 ms |
| 전체 | 637 ms | 761 ms |

같은 PC 안에서 잰 값이라, 실제 이동통신망에서는 전달 구간에 수십~수백 ms가 더해질 수 있습니다. 자세한 내용은 [`docs/latency.md`](docs/latency.md)에 있습니다.

## 참고

- 패키지 이름 `com.hyperconnect.app`은 개발 중 가제(HyperConnect)의 흔적입니다. 앱 표시 이름은 OverRoad입니다.
- 차로 번호는 시연용 입력값입니다. 실차 Tier 1은 일반 GPS를 쓰므로 차로 단위 식별은 Tier 2(센서 공유) 이후의 과제입니다.
- 이모트 소리는 모두 직접 합성했습니다(`scripts/make_emote_sounds.py`). 게임이나 다른 작품의 소리·그림은 쓰지 않았습니다.
