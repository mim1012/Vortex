## 카카오T 드라이버: 예약콜 관련 View ID 전체 목록

디컴파일된 APK의 레이아웃 XML 파일을 분석하여 예약콜 자동화 흐름에 필요한 모든 View ID를 추출하고 정리했습니다.

### 1. 예약콜 리스트 아이템 (`item_reservation_list.xml`)

콜 카드 하나를 구성하는 모든 UI 요소의 ID입니다. **이 목록이 파싱의 핵심입니다.**

| View ID | 타입 (Type) | 설명 (Description) |
| :--- | :--- | :--- |
| `vg_item` | `FrameLayout` | **카드 전체를 감싸는 컨테이너 (클릭 대상)** |
| `vg_content` | `LinearLayout` | 텍스트 콘텐츠를 감싸는 내부 레이아웃 |
| `tv_reserved_at` | `TextView` | **예약 시간 및 종류** (예: `01.10(토) 13:20 / 1시간 예약`) |
| `tv_path` | `TextView` | **출발지 및 도착지 경로** (예: `(청담동) ... → (서울 전농1동) ...`) |
| `tv_pets` | `TextView` | **반려동물 정보** (예: `(부천 중2동) 계남중학교`) - 실제로는 다른 정보가 표시될 수 있음 |
| `v_divider` | `View` | 경로와 요금 정보를 구분하는 회색 선 |
| `tv_stopovers` | `TextView` | **경유지 정보** (예: `경유지 1개`) |
| `v_stopovers_divider` | `View` | 경유지와 요금을 구분하는 수직 선 |
| `tv_fare` | `TextView` | **요금** (예: `요금 50,000원`) |
| `tv_surge` | `TextView` | **할증 정보** (예: `1.1배 피크`) |
| `tv_appointed_driver`| `TextView` | "지명콜" 같은 특수 상태 배지 (기본 숨김) |
| `tv_departure` | `TextView` | "출발임박" 같은 특수 상태 배지 (기본 숨김) |
| `tv_suspend` | `TextView` | "보류" 상태 배지 (기본 숨김) |
| `tv_cancel` | `TextView` | "취소" 상태 배지 (기본 숨김) |

### 2. 예약콜 리스트 화면 (`fragment_reservation_scheduled.xml`)

예약콜 리스트 탭의 전체 구조입니다.

| View ID | 타입 (Type) | 설명 (Description) |
| :--- | :--- | :--- |
| `vg_reservation_list` | `androidx.recyclerview.widget.RecyclerView` | **전체 콜 리스트를 담는 스크롤 가능한 컨테이너** |
| `ll_contents` | `LinearLayout` | RecyclerView를 감싸는 레이아웃 |
| `ll_empty` | `androidx.constraintlayout.widget.ConstraintLayout` | 콜이 없을 때 표시되는 "맞춤콜이 없습니다" 화면 |
| `tv_empty_title` | `TextView` | 콜 없음 화면의 제목 텍스트 |
| `tv_empty_desc` | `TextView` | 콜 없음 화면의 설명 텍스트 |

### 3. 예약콜 상세 화면 (`activity_reservation_detail.xml`)

콜 카드를 클릭했을 때 진입하는 상세 정보 화면입니다.

| View ID | 타입 (Type) | 설명 (Description) |
| :--- | :--- | :--- |
| `btn_call_accept` | `Button` | **"콜 수락" 버튼** |
| `fl_call_accept` | `FrameLayout` | "콜 수락" 버튼을 감싸는 레이아웃 |
| `ll_call_accept` | `LinearLayout` | "콜 수락" 버튼의 visibility를 제어하는 상위 레이아웃 |
| `pb_accept_loading` | `ProgressBar` | "콜 수락" 버튼 클릭 시 나타나는 로딩 스피너 |
| `action_close` | `ImageView` | 화면 우측 상단의 "닫기" 버튼 |
| `map_view` | `MapView` | 출발/도착지가 표시되는 지도 뷰 |
| `vg_path_info` | `ViewGroup` | 출발지, 경유지, 도착지 정보 전체를 담는 그룹 |
| `vg_reservation_call_info` | `ViewGroup` | 요금, 인원, 요청사항 등 부가 정보를 담는 그룹 |

### 4. 수락 확인 다이얼로그 (`dialog_reservation_warning.xml`)

"콜 수락" 버튼 클릭 후 나타나는 최종 확인 팝업입니다.

| View ID | 타입 (Type) | 설명 (Description) |
| :--- | :--- | :--- |
| `btn_positive` | `Button` | **"수락하기" 버튼** |
| `btn_negative` | `Button` | **"취소" 버튼** |
| `tv_title` | `TextView` | 다이얼로그 제목 (예: "예약콜 수락하시겠습니까?") |
| `tv_message` | `TextView` | 다이얼로그 본문 메시지 |
| `iv_warning` | `ImageView` | 경고 아이콘 |
| `v_button_wrapper` | `View` | 두 버튼을 감싸는 컨테이너 |
