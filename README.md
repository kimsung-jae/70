# 보글사다리 삼치기 META90 V1.6

- 매 회차 반드시 4개 조합 중 1개를 제외하고 나머지 3개를 추천합니다.
- 90% 후보가 없다고 대기/재탐색 상태로 멈추지 않습니다.
- 최근희소, Combo Markov, 전이, Shape, kNN, Run, Lag와 META90 동적 규칙선택을 사용합니다.
- META90는 직전 구간에서 실제 제외 실패가 가장 적었던 핵심 규칙을 매 시점 다시 선택합니다.
- 가중치 합산 대신 규칙 선택 및 서로 다른 계열 1표 합성을 사용합니다.
- 90%는 독립 holdout까지 실제 90% 이상일 때만 `90+ 독립검증 통과`로 표시합니다.
- 90% 미달이어도 현재 최저위험 제외 1개를 사용해 삼치기 추천 3픽을 계속 냅니다.

## GitHub Codespaces
```bash
bash 보글사다리_삼치기_META90_V1.6_원클릭.sh
```

Actions > Build Android APK > Artifacts > `BubbleTriPick90V16-debug-apk`

패키지: `com.bubbleladder.tripick90v16`
