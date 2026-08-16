# 보글사다리 AUTO FIND V1.3

## 핵심
- PASS 없음: 데이터가 1회만 있어도 임시 1픽, 충분한 데이터가 있으면 자동 재탐색 1픽을 항상 표시
- 기본 MHS(Markov + HMM + Shape AI)는 진단용으로 유지
- MHS가 약하면 자동으로 다른 방법을 탐색
  - Markov order 1~4
  - 변화 N-gram 2~6
  - Shape 3~7칸 / Hamming 0~1
  - k-NN 유사상황
  - Run-Length
  - Lag 상관
  - Regime
  - 최근빈도
  - 각 규칙의 정방향 / 역방향
- 합성은 서로 다른 3계열을 각각 1표씩 사용하며 가중치 없음
- 70% 독립재현 규칙이 있으면 우선 사용
- 없으면 70 → 65 → 60 → 55 → 50% 단계로 탐색문턱을 넓혀 첫 안정 규칙을 채택
- raw % 최고값을 그대로 고르는 방식이 아니라 탐색순서 + 안정성 문턱 방식
- 70% 미달이어도 대기하지 않고 반드시 추천

## % 표시
화면의 검증률은 선택한 방법의 과거 walk-forward 재현값입니다. 미래 적중률 보장이 아닙니다. 전체 검증구간을 보며 구제 규칙을 찾은 경우에는 독립 70% 인증으로 표시하지 않습니다.

## 자동 기능
- 베픽 공식 API 자동조회
- 백그라운드 자동추첨
- 이전 추천 자동채점
- 최신 최대 150회 누적 분석
- 백업/복원, 실전성적 초기화, 전체 초기화

## 설치
GitHub 새 저장소에 `보글사다리_AUTO_FIND_V1.3_원클릭.sh` 하나를 올린 뒤 Codespaces 터미널에서:

```bash
bash 보글사다리_AUTO_FIND_V1.3_원클릭.sh
```

Actions > Build Android APK > Artifacts > `BubbleAutoFindV13-debug-apk`

패키지: `com.bubbleladder.autofindv13` (기존 앱과 별도 설치)
