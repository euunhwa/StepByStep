# Yaw 적분과 R단 세그먼트로 기동을 잡아낸다

좌·우회전, U턴, 급출발·급정지, 평행·직각 주차는 **규칙 기반 알고리즘으로 충분히 자동 인지할 수 있다**. 핵심 특징량은 조향각이 아니라 **yaw rate를 적분한 누적 heading 변화량(Δψ)**이고, 여기에 속도 하한, 시간창, 기어(R단) 상태를 조합한다. 한국교통안전공단(KOTSA) 기준이 이미 이 구조를 쓴다. 회전은 누적 회전각 60~120°, U턴은 160~180°로 판정하고, 승용차에 가장 가까운 **택시 기준**으로는 급출발 10 km/h/s, 급감속·급정지 14 km/h/s, 급회전은 30 km/h 이상에서 3초 안에 회전을 마친 경우다 ([KR101803662B1](https://patents.google.com/patent/KR101803662B1/ko)). 주차는 R단 진입부터 P단 또는 시동 OFF까지를 한 세그먼트로 묶는다. 순 heading 변화가 약 90°이면 직각주차, 차선변경 형태의 S자 뒤에 후진이 이어지면 평행주차로 본다 ([US20230100346](https://ipiq.io/patents/556485)). 모범 주행 여부는 기동마다 피크 횡가속(교차로 회전 기준 **0.25 g 이하**, 실측 운전자의 81%가 충족), 종방향 가감속 **2 m/s² 이하**, jerk **0.9 m/s³ 이하**로 판정하는 것이 문헌과 맞는다. 감점은 운전면허 도로주행시험의 **5/7/10점 + 실격** 3단 구조와 UBI식 **100 km당 이벤트 빈도 정규화**를 결합하는 방식을 권한다. 공개 데이터셋 중에는 회전·U턴용 HDD, 급가감속 품질용 CAN-DSAD와 UAH-DriveSet이 쓸 만하다. 다만 **실차 주차 기동과 기어 신호를 함께 담은 공개 데이터는 찾지 못했다**. 머신러닝은 회전·U턴·주차·급가감속 인식에는 필요하지 않고, 차선변경과 곡선로 구분, 다회 절환 주차처럼 경계가 모호한 사례에만 경량 모델로 덧붙이면 된다.

## 조향각보다 yaw rate 적분이 기동의 "양"을 정확히 잰다

조향각을 그대로 thresholding하면 차종과 속도에 따라 결과가 흔들린다. 승용차 steering ratio는 **12:1~20:1**로 차종마다 다르고, variable ratio steering은 rack 위치에 따라 기어비가 바뀌므로 상수 하나로 핸들각을 바퀴각으로 환산하면 오차가 생긴다 ([Wikipedia: Steering ratio](https://en.wikipedia.org/wiki/Steering_ratio)). Bicycle model의 선형 근사도 **약 4° 이하**에서만 유효하다 ([PWr 강의자료](https://kip.pwr.edu.pl/download/IaLW09DThOVSlORywcH3xGeCpoa1ZRGiEvMg47cD4rOwolQA1bE2ITehhqNR4nFGoyby4-DRcaJCQlMip2PDE3FyJxNU08dQE,7LfiVtVWxtRD5BIWpAZ2tlKnk6J0NbOXJ8YV1oI214aFl2YzYfLW0FeAE/steering_systems_and_cornering.pdf)). 교과서 관계식 r = v·δ/(L + K·v²)을 보면, 같은 핸들각이라도 속도가 바뀌면 yaw rate가 달라진다. 그래서 고속 차선변경(작은 핸들각)과 저속 교차로 회전(큰 핸들각)을 핸들각 하나로 비교할 수 없다. 반면 yaw rate는 차량이 실제로 돈 양을 직접 잰다. 스마트폰 gyroscope z축만 쓰는 V-Sense도 yaw rate를 적분한 heading으로 회전과 차선변경을 구분했고, 좌·우회전 검출률 **100%**를 보고했다 ([V-Sense](https://web.eecs.umich.edu/~chendy/files/VSense.pdf)).

따라서 설계는 역할을 나눈다. 회전 총량은 yaw rate 적분으로 재고, 조향각은 S자 패턴(부호 반전) 검출, 주차 중 full-lock 근접 여부, 운전자 의도의 선행 신호를 보는 보조 특징으로 쓴다. 저속에서는 GPS heading 잡음이 커지므로 주차 기동에서는 gyro 또는 CAN yaw rate 적분이 사실상 필수다. 샘플링 주기도 중요하다. KOTSA 기준은 1 Hz DTG의 1초 단위 속도 차분을 전제로 한다. 10 Hz 이상의 IMU를 쓰면 Damoov처럼 **지속시간 600 ms 조건**을 함께 둬야 스파이크를 걸러낼 수 있다 ([Damoov docs](https://docs.damoov.com/docs/events-detection)).

### KOTSA는 차종별로 다른 임계값을 쓰고, 승용차에는 택시 열이 맞다

현재 공개적으로 확인되는 가장 완전한 수치는 교통안전공단이 2016년에 출원한 특허 KR101803662B1의 표 1이다. 이 표는 화물·버스·택시 3개 차종의 기준을 모두 담고 있고, 차가 무거울수록 임계값이 낮다(화물 < 버스 < 택시) ([KR101803662B1](https://patents.google.com/patent/KR101803662B1/ko)). 화물 수치는 2022년 논문과 일치하고 ([이현미 외 2022](https://koreascience.kr/article/JAKO202223753941747.pdf)), 택시 수치는 2018년 공격운전 검지 연구가 eTAS 기준으로 인용한 값과 같다 ([김윤진·오철 외 2018](https://koreascience.kr/article/JAKO201810256455319.pdf)). 다만 버스 급U턴은 특허(20 km/h·8초)와 버스 논문(25 km/h·9초)의 값이 다르다 ([김수재 외 2018](https://koreascience.kr/article/JAKO201814442073557.pdf)). 2015년 이전의 구 기준은 차종 구분 없이 급가속 11 km/h/s, 급회전 15 km/h 이상·60° 이상이었고 U턴 항목이 없었다 ([김유원·강준규 2015](https://koreascience.kr/article/JAKO201507964682780.pdf)). 수치 임계값이 법령이나 고시에 명시된 것은 확인되지 않았다. 문헌은 모두 eTAS 자체 정의나 공단 내부 보고서를 출처로 든다. 특허는 고정 임계값 대신 기준속도의 5차 다항식으로 임계값을 바꾸는 방식도 제안하지만, 계수값은 확보하지 못했다.

택시 기준을 업계값과 비교하면 위치가 보인다. 급가속 8 km/h/s는 약 0.23 g, 급감속 14 km/h/s는 약 0.40 g다(환산은 작성자 계산). Geotab 기본값은 가속 0.4 g, 제동 0.6 g, 코너링 0.5 g이고 ([Geotab](https://www.geotab.com/blog/event-triggers)), Damoov는 가감속 2.7 m/s², 코너링 3 m/s²에 25 km/h 속도 조건을 둔다 ([Damoov](https://docs.damoov.com/docs/events-detection)). 즉 KOTSA 택시 기준은 업계 범위의 하단~중간으로, 위험 이벤트를 비교적 민감하게 잡는다.

## 인식용 기준표: 승용차 권장값

아래 표에서 **[출처]**는 문헌에 그대로 나오는 값이고, **[추론]**은 문헌 수치를 조합하거나 계산해 작성자가 제안하는 값이다. 단위 환산은 1 km/h/s = 0.2778 m/s² ≈ 0.0283 g를 썼다(작성자 계산).

| 상황 | 트리거·분할 조건 | 판정 기준(승용차 권장) | 근거 |
|---|---|---|---|
| 정차 판정 | 속도 ≈ 0 | 4~5 s 창 표준편차 등으로 idle 판정 | [출처] [US20230100346](https://ipiq.io/patents/556485) |
| 급출발 | 시작 속도 v ≤ 5 km/h | 가속 ≥ **10 km/h/s**(2.78 m/s², 0.28 g) | [출처] 택시 열, [KR101803662B1](https://patents.google.com/patent/KR101803662B1/ko) |
| 급가속 | v ≥ 6 km/h | 가속 ≥ **8 km/h/s**(2.22 m/s²) | [출처] 택시 열 |
| 급감속 | v ≥ 6 km/h | 감속 ≥ **14 km/h/s**(3.89 m/s²) | [출처] 택시 열 |
| 급정지 | 감속 후 v ≤ 5 km/h로 종료 | 감속 ≥ **14 km/h/s** | [출처] 택시 열 |
| 고빈도 IMU 사용 시 | 10 Hz 이상 | 임계 초과가 **600 ms 이상** 지속될 때만 인정 | [출처] [Damoov](https://docs.damoov.com/docs/events-detection) |
| 회전 구간 시작·끝 | 이동평균한 yaw rate | 모든 샘플 ≥ **0.05 rad/s**(2.9°/s), peak ≥ **0.07 rad/s**(4.0°/s), 지속 ≥ **1.5 s**인 bump | [출처] [V-Sense](https://web.eecs.umich.edu/~chendy/files/VSense.pdf) |
| 좌·우회전 | bump 1개, 부호로 좌(+)/우(−) | 누적 Δψ **60~120°** | [출처] KOTSA 택시 열; 대표값 ±90° [V-Sense](https://web.eecs.umich.edu/~chendy/files/VSense.pdf) |
| 급좌·우회전 | 위 회전 중 | 속도 ≥ **30 km/h**, **3 s 이내** 완료(평균 yaw rate ≥ 약 20°/s) | [출처] 택시 열; 괄호 안 값은 [추론] 60°/3 s |
| U턴(인식) | bump 1개 | 누적 Δψ **150~210°** | [추론] KOTSA 160~180° 상한이 V-Sense 실측 평균 185.99°를 놓치므로 확장 |
| U턴(eTAS 호환) | 위와 같음 | 누적 Δψ 160~180° | [출처] [KR101803662B1](https://patents.google.com/patent/KR101803662B1/ko) |
| 급U턴 | U턴 중 | 속도 ≥ **25 km/h**, **6 s 이내** | [출처] 택시 열 |
| 진로변경 | 헤딩 15~30° | 각속도 ≥ 10°/s, v ≥ 30 km/h | [출처] [김윤진 외 2018](https://koreascience.kr/article/JAKO201810256455319.pdf) |
| 차선변경(형태) | 반대 부호 bump 쌍, 두 번째 bump는 3 s 안에 | 횡변위 ≈ 차로폭 3.65 m이면 차선변경, 넘으면 곡선로 | [출처] [V-Sense](https://web.eecs.umich.edu/~chendy/files/VSense.pdf) |
| 주차 세그먼트 | R단 진입 → P단/시동 OFF/장시간 정차 | 직전 전진 구간과 R/D 절환 구간을 하나의 주차 이벤트로 누적 | [출처] 시퀀스 모델링 [US20230100346](https://ipiq.io/patents/556485); 묶는 범위는 [추론] |
| 직각(T자) 주차 | 주차 세그먼트 | 후진 접근각 α 20~90°, 최종 α′ = 90° − α. 이동거리 차량 길이의 2~3배 | [출처] [US20230100346](https://ipiq.io/patents/556485) |
| 직각 주차(실무 판정) | 위와 같음 | 전진+후진 합산 순 \|Δψ\| **70~110°**, 핸들각이 한 방향 full-lock 근처로 유지 | [추론] |
| 평행 주차 | 주차 세그먼트 | "lane change에 이어지는 reverse drive". 이동거리 차량 길이의 1~2배 이하, 일반 주차 기동 5~10 m | [출처] [US20230100346](https://ipiq.io/patents/556485) |
| 평행 주차(실무 판정) | 위와 같음 | 순 \|Δψ\| **< 15~20°**, 후진 중 핸들각 부호 반전(S자), yaw rate +/− 쌍 | [추론] |

직각주차에서 함정이 하나 있다. 운전자는 전진하면서 일부 회전한 뒤 후진하는 경우가 많아서(특허의 α + α′ = 90°), 후진 구간의 Δψ만 보면 45~70°처럼 90°보다 작게 나올 수 있다. 직전 전진 구간까지 합산하거나, 주차 직전 도로 heading 대비 최종 heading을 비교해야 한다. 회전 구간의 끝점은 V-Sense의 bump 조건으로 잡고, 일반 회전과 "급" 회전을 같은 bump에서 속도·시간 조건만으로 분기하면 인식과 위험도 판정이 깔끔하게 분리된다.

## 모범 주행 판정은 횡가속·가감속·jerk와 5/7/10 감점 구조로 한다

인식이 "무엇을 했는가"라면 평가는 "얼마나 부드럽고 안전하게 했는가"다. 문헌이 가장 단단하게 받쳐 주는 지표는 조향각 자체가 아니라 **가속도 계열**이다. 인간 운전 제동의 **99%가 −0.5~−2 m/s²** 범위에 있고, 비상 회피 최대 감속은 약 −5.1 m/s²다. 착석 승객의 편안함 한계로는 약 2 m/s²와 jerk 0.9 m/s³가 제안됐다. 공격적 프로파일 예시는 종방향 +2.2/−2.5 m/s², 횡방향 3.5 m/s², jerk 1.5 m/s³이고, 운전자 선호 명목치는 횡 0.4 g, 종 0.2 g다 ([arXiv 2001.03908](https://arxiv.org/pdf/2001.03908.pdf)). ISO 15622(ACC)는 속도가 높아질수록 감속 한계를 −5에서 −3.5 m/s²로 낮춘다 ([arXiv 1911.11805](https://arxiv.org/pdf/1911.11805)). 교차로 좌회전 실측값(n=86)은 피크 횡가속 평균 **0.17 g**, **81%가 0.25 g 미만**, 진입·진출 속도 약 10.3/15.4 km/h였고, 대향 2차로를 가로지르는 그룹은 0.29 g로 가장 높았다 ([Carter et al., SAE 2019-01-0421](https://jsheld.com/uploads/PDFs/Lateral-and-Tangential-Accelerations-of-Left-Turning-Vehicles-from-Naturalistic-Observations.pdf)).

임계값 선택이 결과를 얼마나 바꾸는지도 알아 둬야 한다. 문헌의 "급" 이벤트 임계값은 0.1 g에서 0.91 g까지 퍼져 있고, 그 결과 이벤트 빈도가 **0.04건/km에서 14.6건/km**까지 수백 배 차이 난다 ([Joubert et al. 2016](https://core.ac.uk/download/80914881.pdf)). 그러므로 고정 임계값은 출발점일 뿐이다. 자체 데이터가 쌓이면 Joubert처럼 분포 분위수로 등급 경계를 다시 맞추는 편이 낫다.

조향각 변화량에 대해서는 정직하게 말해야 한다. **yaw rate나 steering rate(°/s)의 편안함 임계값을 다룬 1차 자료는 찾지 못했다.** 도로주행시험도 "과도·지연 조향 보정"을 −7점으로 정성 판정할 뿐이다. 그래서 조향 품질은 두 가지로 간접 평가할 것을 권한다. 첫째, 허용 횡가속을 kinematic bicycle model로 조향각 상한으로 바꾼다(δ_SW ≈ i_s·L·a_y/v²). 예를 들어 L = 2.7 m, i_s = 15, v = 30 km/h, a_y = 0.25 g이면 핸들각 약 82°가 된다(작성자 계산이며 저속 운동학 영역에서만 유효). 둘째, 한 기동 안에서 조향 각속도 부호가 반전되는 횟수를 "보정 조향 횟수"로 세어 −7점 항목에 매핑한다(추론).

### 평가용 기준표: 기동별 3단 등급(승용차 권장)

| 지표 | 모범 | 주의 | 위험 | 근거 |
|---|---|---|---|---|
| 종방향 감속(정지·감속) | ≤ 2 m/s² | 2~3.5 m/s² | > 3.5 m/s²(KOTSA 급감속 3.89 m/s²) | 모범 경계 [출처] 제동 99% 범위 [arXiv 2001.03908](https://arxiv.org/pdf/2001.03908.pdf); 위험 경계 [출처] ISO 15622 고속 한계 [arXiv 1911.11805](https://arxiv.org/pdf/1911.11805); 3단 구분은 [추론] |
| 종방향 가속(출발·가속) | ≤ 2 m/s² | 2~2.78 m/s² | ≥ 2.78 m/s²(급출발 10 km/h/s) | 모범 [출처] 편안함 한계 약 2 m/s²; 위험 [출처] KOTSA 택시 급출발; 구간 분할 [추론] |
| jerk | ≤ 0.9 m/s³ | 0.9~1.5 m/s³ | > 1.5 m/s³ | [출처] 편안함 범위 [−0.9, 0.9] [arXiv 2509.01291](https://arxiv.org/pdf/2509.01291), 공격적 프로파일 1.5 [arXiv 2001.03908](https://arxiv.org/pdf/2001.03908.pdf); 스마트폰 IMU는 저역통과 필터 후 가중을 낮춤 [추론] |
| 좌·우회전 피크 횡가속 | ≤ 0.25 g | 0.25~0.4 g | > 0.4 g | 모범 [출처] [Carter 2019](https://jsheld.com/uploads/PDFs/Lateral-and-Tangential-Accelerations-of-Left-Turning-Vehicles-from-Naturalistic-Observations.pdf); 0.4 g [출처] 운전자 선호 명목치; 위험 경계로 쓰는 것은 [추론] |
| 회전 진출 속도 | 약 15 km/h 전후 | — | ≥ 30 km/h로 3 s 안에 회전(급회전) | [출처] Carter 실측, KOTSA 택시 열 |
| U턴 속도 | v ≤ √(0.2 g·R), R = 6 m이면 약 12 km/h | — | ≥ 25 km/h로 6 s 안에(급U턴) | 상한식 [추론] 계산; 위험 [출처] KOTSA |
| 주차 소요시간 | ≤ 120 s | 120 s 초과마다 −10 | 연석 접촉은 실격 | [출처] 기능시험 별표 24 직각주차 [law.go.kr](https://www.law.go.kr/LSW/flDownload.do?flSeq=168840619) |
| 주차 R/D 절환 횟수 | 직각 1회, 평행 2회 이내 | 초과 1회마다 −5 | — | [추론] |

### 감점 가중은 면허시험 구조에 거리 정규화를 얹는다

한국 운전면허시험 채점표는 이미 검증된 가중 구조를 갖고 있다. 도로주행시험은 100점 감점제에 70점 이상 합격, 46개 항목이다. 감점은 안전 직결 항목(진로변경 안전 미확인, 교차로 서행·일시정지 미이행, 횡단보도 앞 미정지) **−10**, 조작 품질 항목(급브레이크, 급출발, 조향 보정, 방향지시등 미작동) **−7**, 형식 항목(종료 시 기어·주차브레이크) **−5**로 나뉜다. 제한속도 10 km/h 초과, 중앙선 침범, 3회 이상 급정지·급출발은 **실격**이다 ([별표 26](https://www.law.go.kr/LSW/flDownload.do?flSeq=168840641)). 장내기능시험은 80점 이상 합격이고, 직각주차에서 검지선 접촉이나 120초 초과 시 −10, 좌·우회전 방향지시등 미점등 시 −5를 준다 ([별표 24](https://www.law.go.kr/LSW/flDownload.do?flSeq=168840619)). 두 별표는 요약 경유로 읽은 내용이므로 반영 전에 law.go.kr 원문과 대조해야 한다.

주행 전체(trip) 점수에는 UBI 방식이 맞다. Digital Matter는 100점에서 출발해 카테고리별 최대 차감을 급가속 15, 급제동 25, 급코너링 30, 과속 30으로 두고, 카테고리 점수를 "가중치 − 이벤트 수 × 100 / 총거리"로 계산한다 ([Digital Matter](https://support.digitalmatter.com/speed-monitoring-driver-behaviour/driver-scoring-reports)). Progressive Snapshot은 7 mph/s(약 0.32 g)를 hard brake 기준으로 쓴다 ([Progressive](https://progressive.com/Snapshot/tdx/FAQGeneral/FAQ)). TMAP은 3초 안에 ±30 km/h(약 2.8 m/s²) 변화와 제한속도 +15 km/h 과속을 쓰고, 500 km 구간 점수를 거리 가중 평균하며 야간 이벤트를 더 크게 감점하는 것으로 알려져 있다. 다만 이 내용은 2차 블로그 자료다 ([specialmanual.com](https://specialmanual.com/%ED%8B%B0%EB%A7%B5-%EC%9A%B4%EC%A0%84%EC%A0%90%EC%88%98-%EC%98%AC%EB%A6%AC%EA%B8%B0-%EB%B0%A9%EB%B2%95-6%EA%B0%80%EC%A7%80-%EC%9A%B4%EC%A0%84%EC%A0%90%EC%88%98-%EC%82%B0%EC%A0%95-%EB%B0%A9%EC%8B%9D/)). 학술 쪽으로는 Joubert가 위험 등급(none/low/med/high)에 가중 0/1/2/3을 주고 모집단 안에서 재정규화했다 ([Joubert 2016](https://core.ac.uk/download/80914881.pdf)). 위반량에 비례해 감점하려면 지수형 페널티 ψ(x) = (1/M)(e^{Mpx} − 1)을 쓸 수 있다 ([arXiv 2509.01291](https://arxiv.org/pdf/2509.01291)).

이를 합쳐 다음 2층 구조를 권한다(설계 제안, 추론). 기동 점수는 기동마다 100점에서 시작한다. 위 평가표에서 "주의"는 −5, "위험"은 −7, 안전 직결 위반(회전 시 방향지시등 미점등, 횡단보도 앞 미정지 등 신호가 있을 때)은 −10을 깎는다. 경계를 넘은 양은 지수 페널티로 추가 감점할 수 있다. 실격 조건(제한속도 +10 km/h 초과, 연석 접촉 등)은 점수와 별도 플래그로 남긴다. 주행 점수는 이벤트 수를 100 km당 빈도로 정규화하고, 야간·보호구역·교차로 같은 맥락 가중을 곱해 카테고리별 최대 차감 안에서 깎는다. 짧은 주행에서 점수가 크게 흔들리지 않도록 최소 거리 미만이면 사전 평균 쪽으로 축소한다.

## 공개 데이터셋은 회전·급가감속은 충분하고, 주차는 직접 모아야 한다

차량 신호와 기동 라벨을 함께 갖춘 공개 데이터셋은 사실상 **HDD**가 유일하다. HDD는 104시간 137세션 분량에, CAN 100 Hz 신호(throttle, brake pressure, steering angle, yaw rate, speed)와 좌·우회전·U턴 등 11종 goal-oriented 라벨, stop stimulus 라벨을 담고 있다 ([HDD arXiv 1811.02307](https://ar5iv.labs.arxiv.org/html/1811.02307)). 운전 품질 라벨 쪽에서는 CAN-DSAD가 요구사항에 가장 가깝다. 차속, RPM, 브레이크, **gear position**, 초음파 센서 신호와 함께 normal/aggressive braking/aggressive lane change/aggressive acceleration 라벨이 있다 ([IEEE DataPort CAN-DSAD](https://ieee-dataport.org/documents/real-time-detection-aggressive-driving-using-can-data-differentiating-drivers-styles-and)). 실차 주차 데이터셋(PSV, DeepPS, SNU Parking)은 surround-view 이미지뿐이고 차량 상태나 제어 신호가 없다. 주차 궤적과 제어값이 있는 쪽은 시뮬레이션 데이터 ParkingScenes가 가장 가깝다 ([arXiv 2604.22835](https://arxiv.org/html/2604.22835v1)).

| 데이터셋 | 핵심 신호 | 라벨 | 접근 | 용도 |
|---|---|---|---|---|
| HDD | CAN 100 Hz(속도, 조향각, yaw rate, 페달), GPS/IMU 120 Hz. gear 없음 | 좌·우회전, U턴, 차선변경, stop 등 | 신청 후 다운로드 | 회전·U턴 인식 학습·검증 ([HDD](https://ar5iv.labs.arxiv.org/html/1811.02307)) |
| CAN-DSAD | 속도, RPM, 브레이크, gear position, 초음파(hex, 디코딩 필요) | normal / aggressive 3종 | CC BY 4.0, IEEE DataPort 구독 | 급가감속 품질 평가, 기어 신호 ([DataPort](https://ieee-dataport.org/documents/real-time-detection-aggressive-driving-using-can-data-differentiating-drivers-styles-and)) |
| UAH-DriveSet | 스마트폰 센서, GPS, 영상 | normal / drowsy / aggressive | 연구용 공개 | 스마트폰 기반 품질 기준선 ([arXiv 2111.04794](https://arxiv.org/pdf/2111.04794)) |
| nuScenes CAN bus | yaw_rate, steering, `gear_position`(2 Hz), 방향지시등 | 기동 라벨 없음 | nuScenes 약관 | gear·방향지시등 신호 형식 참고. scene이 약 20초로 짧고, 문서에는 0=parked, 7=driving만 있어 R 값 미기재 ([devkit](https://github.com/nutonomy/nuscenes-devkit/blob/master/python-sdk/nuscenes/can_bus/README.md)) |
| comma2k19 | CAN(속도, 조향각, 휠속도, raw CAN), IMU, GNSS | 없음 | MIT | 비지도 학습, 규칙 기반 pseudo-label ([GitHub](https://github.com/commaai/comma2k19)) |
| Udacity | 조향각, 속도, 브레이크, 스로틀, gear | 없음 | 무료 공개 | gear 신호 테스트(약 70분) ([Medium](https://medium.com/udacity/open-sourcing-223gb-of-mountain-view-driving-data-f6b5593fbfa5)) |
| HCRL Driving Dataset | OBD-II CAN 특징, 한국 도로 약 23시간 | 운전자 ID | 학술용 | 한국 도로 스타일 클러스터링 ([HCRL](https://sites.google.com/hksecurity.net/hcrl/Datasets/driving-dataset)) |
| HCRL Car-Hacking | raw CAN(ID, DLC, DATA) | 공격/정상 | 학술용 | gear CAN ID 역추적 단서 ([HCRL](https://sites.google.com/hksecurity.net/hcrl/Datasets/car-hacking-dataset)) |
| ParkingScenes | CARLA 위치·속도·가속도·조향각, 제어값 | reverse-in 16종 + parallel 6종, 704 episode | GitHub | 주차 시뮬레이션 보강 ([arXiv 2604.22835](https://arxiv.org/html/2604.22835v1)) |
| AI Hub "사고위험 환경에서의 운전습관 데이터" | 미확인(로그인 필요) | 미확인 | 신청·승인 | 국내 품질 평가 후보, 스키마 확인 필요 ([AI Hub](https://www.aihub.or.kr/aihubdata/data/list.do?currMenu=115&topMenu=100&searchKeyword=%EC%A3%BC%ED%96%89)) |

주차 데이터는 직접 수집해야 한다. OBD-II 포트에 CAN 인터페이스를 연결하고 opendbc(comma.ai)의 차종별 DBC로 기어, 조향각, 휠속도, yaw rate, 방향지시등을 디코딩한다. 표준 OBD-II PID에는 gear와 조향각이 없으므로 ELM327 같은 PID 동글로는 부족하다. 대시캠 영상을 동기화해 기동 구간을 사람이 태깅하되, R단 구간은 CAN에서 자동으로 분할하면 라벨링 비용이 크게 준다. 이 수집 구성은 연구 노트의 권장안이며, 현대·기아 DBC가 opendbc에 있는지는 확인하지 못했다.

## 머신러닝은 경계 사례에만 필요하다

규칙만으로 충분한 영역과 ML이 필요한 영역은 증거로 갈린다. 규칙 기반 V-Sense는 회전 **100%**, 차선변경 **85~93%**, 곡선로 **92~97%**를 달성했고 계산량도 카메라 방식보다 적었다 ([V-Sense](https://web.eecs.umich.edu/~chendy/files/VSense.pdf)). 반면 HDD에서 학습한 CAN-only 모델은 우회전 AP 74.27, 좌회전 66.25, U턴 33.57이었지만 **좌·우 차선변경은 26.17/13.39, 합류는 3.59**에 그쳤다. 카메라를 융합해도 mAP는 23.84에서 32.71로 오르는 데 그쳤다 ([HDD](https://ar5iv.labs.arxiv.org/html/1811.02307)). 두 수치는 과제가 다르다(HDD는 연속 스트림의 프레임 단위 AP, V-Sense는 분할된 이벤트 단위 정확도). 그래도 공통된 메시지는 분명하다. 큰 heading 변화를 동반하는 기동은 쉽고, 작은 heading 변화로 구분해야 하는 미세 기동은 어렵다. 예측(anticipation) 과제인 Brain4Cars에서는 시계열 모델이 정적 분류기를 크게 앞섰다(SVM precision 43.7% 대 LSTM fusion 84.5%) ([Brain4Cars](https://arxiv.org/pdf/1601.00740)). 이는 시간 맥락이 중요한 과제에서 HMM이나 LSTM이 값을 한다는 뜻이다.

이 과제의 대상 기동은 대부분 쉬운 쪽이다. 좌·우회전과 U턴은 Δψ 크기로, 급출발·급정지는 속도 조건과 가속도로, 주차는 R단으로 분할된다. 주차 특허도 규칙과 참조 시퀀스 매칭으로 주차 유형을 분류하고, ML은 임계값 자기보정에만 쓴다 ([US20230100346](https://ipiq.io/patents/556485)). ML이 실제로 필요한 곳은 세 군데다. 차선변경과 곡선로 내 조향의 구분, R/D를 여러 번 절환하는 주차의 유형 판정, 그리고 차종·운전자별 임계값 보정이다. 규칙 기반과 ML을 같은 CAN 데이터셋에서 직접 비교한 연구는 찾지 못했다. 따라서 초기에는 규칙으로 시작하고, 규칙 결과를 weak label로 삼아 경계 사례용 데이터셋을 키우는 순서가 현실적이다.

### 권장 아키텍처

```
[수집]   CAN(속도, 조향각, yaw rate, 기어, 방향지시등; opendbc DBC 디코딩)
         └ 대체: 스마트폰 IMU 100 Hz + GPS (yaw rate, 가감속 추정)
           │
[전처리] 10 Hz 정렬 → 이동평균/저역통과 필터 → 정차 판정(4~5 s 창 표준편차)
           │
[분할]   ① 속도 이벤트: v ≤ 5 km/h 경계에서 출발/정지 구간
         ② yaw bump: |r| ≥ 0.05 rad/s, peak ≥ 0.07 rad/s, ≥ 1.5 s
         ③ 주차 세그먼트: R단 진입 ~ P단/시동 OFF (직전 전진·R/D 절환 포함)
           │
[규칙 분류] Δψ 60~120° → 회전(부호로 좌/우) | 150~210° → U턴
            반대 부호 bump 쌍 + 횡변위 ≈ 3.65 m → 차선변경
            주차: 순 Δψ 70~110° → 직각 | < 15~20° + 조향 S자 → 평행
            "급" 판정: KOTSA 택시 열(속도 하한 + 시간창 + km/h/s)
           │
[선택 ML] 경계 사례만: 세그먼트 특징(Δψ, 횡변위, bump 수, 지속시간,
          속도 통계, 조향 부호 반전 수) → RF 또는 1D-CNN
           │
[평가]   기동별 지표(피크 a_lat, a_long, jerk, 소요시간, 절환 수, 방향지시등)
         → 모범/주의/위험 등급 → −5/−7/−10 감점, 실격 플래그
           │
[집계]   trip 점수 = 카테고리 최대 차감 − 100 km당 빈도 × 맥락 가중
         최소 거리 미만이면 사전 평균 쪽으로 축소
```

분할 단계의 세 경로는 서로 독립적으로 돌아서, 정지 직전 우회전이나 후진 직전 정차처럼 겹치는 상황도 각각 잡힌다. 규칙 분류의 경계값 가운데 V-Sense의 bump 파라미터와 KOTSA 수치는 출처가 있다. 반면 U턴 150~210°, 직각 70~110°, 평행 < 15~20°는 추론값이므로, 자체 수집 데이터로 ROC를 그려 확정해야 한다.

## Conclusion

이번 조사로 확인된 가장 중요한 사실은 **기존 국내 기준이 "인식"과 "위험 판정"을 이미 한 구조 안에 담고 있다**는 점이다. KOTSA의 누적 회전각 구간은 기동 종류를 정하고, 속도 하한과 시간창은 위험도를 정한다. 따라서 같은 yaw bump에서 조건만 바꿔 정상 기동과 급기동을 함께 출력하면, 별도 모델 없이 인식기와 위험 검지기를 동시에 얻는다. 단, KOTSA의 U턴 상한 180°는 실측 U턴 평균 186°보다 좁으므로 인식용으로는 넓혀야 한다. 진짜 공백은 두 곳이다. 하나는 조향각·steering rate의 편안함 기준으로, 1차 문헌이 없어 횡가속에서 역산하거나 보정 횟수로 대신해야 한다. 다른 하나는 기어 신호가 붙은 실차 주차 데이터로, 공개된 것이 없다.

실무적으로는 데이터 수집이 알고리즘보다 먼저다. 임계값 하나가 이벤트 빈도를 수백 배 바꾸므로, 문헌값은 초기값으로만 쓰고 자체 CAN 로그와 대시캠 라벨로 차종별 분포를 확보한 뒤 분위수 기반으로 등급 경계를 다시 맞춰야 한다. KOTSA 특허가 제안한 속도 의존 임계값(5차 다항식)도 그 데이터가 있어야 계수를 정할 수 있다. 감점표 역시 면허시험의 5/7/10 비율을 빌려 시작하되, 이벤트와 사고율의 관계를 검증한 가중치는 아직 공개되지 않았으므로 고정값이 아니라 조정 가능한 설정 항목으로 두는 것이 맞다.
