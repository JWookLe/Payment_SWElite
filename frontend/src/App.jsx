import { useEffect, useMemo, useState } from 'react';
import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api'
});

const CATEGORIES = [
  {
    id: 'mobile',
    name: '모바일·태블릿',
    tagline: '새로운 플래그십을 가장 빠르게 받아보세요',
    description: '프리미엄 스마트폰과 태블릿을 하루 만에 받아보고, 카드 추가 할인과 보상 프로그램까지 한 번에 누리세요.',
    accent: '#2563eb',
    heroImage: '/category-mobile.jpg',
    subheading: '오늘 주문하면 내일 도착하는 로켓배송 스마트 디바이스',
    products: [
      {
        id: 'iphone-16-pro',
        name: 'Apple iPhone 16 Pro 256GB',
        brand: 'Apple',
        price: 1690000,
        originalPrice: 1790000,
        rating: 4.9,
        reviews: 1284,
        badges: ['로켓배송', '무료 반품', '사전 예약'],
        shortDescription: 'A18 Pro 칩셋과 120Hz ProMotion OLED 디스플레이, 티타늄 프레임으로 완성한 차세대 플래그십.',
        highlights: [
          'A18 Pro 칩과 6.1형 ProMotion OLED로 놀라운 게임·영상 퍼포먼스',
          '48MP 트리플 카메라와 텔레포토 촬영, ProRes 로그 지원',
          '새로운 티타늄 프레임과 더 가벼운 무게, 향상된 배터리 수명',
          'AppleCare+와 보상 프로그램 선택 가능'
        ],
        shipping: '오늘 출발 · 내일(화) 도착 보장',
        colorOptions: ['화이트 티타늄', '내추럴 티타늄', '데저트 티타늄'],
        monthlyInstallment: '월 70,900원 (24개월, 무이자)',
        promoLabel: '카드 즉시할인 7% + 사은품 증정',
        image: '/iphone-16-pro.jpg'
      },
      {
        id: 'galaxy-s25-ultra',
        name: 'Samsung Galaxy S25 Ultra 512GB',
        brand: 'Samsung',
        price: 1590000,
        originalPrice: 1690000,
        rating: 4.8,
        reviews: 893,
        badges: ['로켓와우 전용가', '당일 설치', '사전 예약'],
        shortDescription: '가장 밝은 QHD+ 디스플레이와 200MP 쿼드 카메라, AI 기반 갤럭시 경험의 정점.',
        highlights: [
          'Vision Booster로 더욱 선명한 6.9형 QHD+ 인피니티 디스플레이',
          '200MP 메인 카메라와 AI 줌, Nightography로 완벽한 저조도 촬영',
          'S펜 내장, Knox 보안과 AI 번역 기능으로 생산성 강화',
          '삼성케어플러스 2년형 추가 가능'
        ],
        shipping: '서울 기준 오늘 야간 도착 · 전국 익일 배송',
        colorOptions: ['팬텀 블랙', '미드나잇 블루', '크림 화이트'],
        monthlyInstallment: '월 66,300원 (24개월, 무이자)',
        promoLabel: '삼성 카드 청구할인 10만원',
        image: '/galaxy-s25-ultra.jpg'
      },
      {
        id: 'ipad-pro-m4',
        name: 'Apple iPad Pro 12.9" M4 Wi-Fi 256GB',
        brand: 'Apple',
        price: 1640000,
        originalPrice: 1740000,
        rating: 4.9,
        reviews: 642,
        badges: ['로켓배송', '신상품', '베스트'],
        shortDescription: 'M4 칩과 울트라 레티나 XDR 디스플레이, Apple Pencil Pro와 완벽 호환.',
        highlights: [
          'M4 칩셋과 10코어 GPU로 그래픽 작업과 영상 편집을 빠르게 처리',
          '울트라 레티나 XDR 디스플레이로 HDR 콘텐츠 감상 최적화',
          'Apple Pencil Pro·매직 키보드 호환, 스튜디오 품질 오디오',
          'Wi-Fi 6E 지원과 최대 10시간 배터리'
        ],
        shipping: '평일 오후 3시 이전 주문 시 당일 출고',
        colorOptions: ['스페이스 블랙', '실버'],
        monthlyInstallment: '월 68,300원 (24개월, 무이자)',
        promoLabel: '교육 고객 전용가 · 정품 파우치 증정',
        image: '/ipad-pro.jpg'
      }
    ]
  },
  {
    id: 'appliance',
    name: '프리미엄 가전',
    tagline: '공간을 완성하는 하이엔드 라이프스타일 가전',
    description: '호텔 스위트룸처럼 연출되는 빌트인 가전부터 공기 청정, 청소까지 프리미엄 라인업을 모았습니다.',
    accent: '#0ea5e9',
    heroImage: '/category-appliance.jpg',
    subheading: '전문 기사 설치부터 사후 케어까지 한 번에',
    products: [
      {
        id: 'dyson-gen5detect',
        name: 'Dyson Gen5 Detect 무선 청소기',
        brand: 'Dyson',
        price: 1290000,
        originalPrice: 1390000,
        rating: 4.7,
        reviews: 522,
        badges: ['당일 설치', '무이자 12개월', '리필 필터 증정'],
        shortDescription: '레이저 슬림 플러피 헤드로 99.9% 먼지를 시각화하며 흡입력을 유지하는 다이슨의 최신형 모델.',
        highlights: [
          '135,000RPM Hyperdymium 모터로 280AW 강력한 흡입력',
          '레이저 슬림 플러피 헤드가 미세먼지를 시각화',
          '피에조 센서가 먼지 양을 측정하고 디스플레이로 확인',
          '무게 중심 설계와 다양한 툴로 손쉬운 청소'
        ],
        shipping: '전국 설치 기사 방문 · 기본 설치 무료',
        colorOptions: ['니켈/퍼플'],
        monthlyInstallment: '월 53,700원 (24개월, 무이자)',
        promoLabel: '다이슨 정품 청소 거치대 포함',
        image: '/dyson-gen5.jpg'
      },
      {
        id: 'lg-object-collection',
        name: 'LG 오브제컬렉션 워시타워 W20S',
        brand: 'LG',
        price: 2290000,
        originalPrice: 2490000,
        rating: 4.9,
        reviews: 311,
        badges: ['전문 기사 설치', '로켓와우 전용 혜택', '현대카드 7% 할인'],
        shortDescription: '세탁부터 건조까지 하나로, 공간 맞춤 컬러로 완성하는 모던한 세탁 공간.',
        highlights: [
          'AI DD 모터가 6가지 세탁패턴을 자동 최적화',
          '듀얼 인버터 히트펌프로 전기료 절감 건조',
          'ThinQ 앱으로 원격 제어 및 진단',
          '오브제컬렉션 전용 컬러로 인테리어 포인트'
        ],
        shipping: '희망일 배송 · 설치 전문 엔지니어 배정',
        colorOptions: ['크림 화이트', '그레이 베이지', '딥 그린'],
        monthlyInstallment: '월 95,400원 (24개월, 무이자)',
        promoLabel: '설치비 무료 + 베이킹소다 세제 증정',
        image: '/lg-washtower.jpg'
      },
      {
        id: 'balmung-air-purifier',
        name: 'Balmuda 더 에어 공기청정기',
        brand: 'Balmuda',
        price: 749000,
        originalPrice: 799000,
        rating: 4.8,
        reviews: 417,
        badges: ['로켓배송', '리뷰 1천+ 기념 할인', '필터 포함'],
        shortDescription: '360도 파워풀 흡입과 클린부스터 토출로 거실 공기를 빠르게 순환시키는 발뮤다 토탈 공기 케어.',
        highlights: [
          '360도 서라운드 흡입과 두 개의 토출구로 빠른 공기순환',
          'H13 등급 플리츠 필터와 활성탄 탈취 필터 기본 제공',
          '정숙 모드 19dB, 야간에도 조용한 운전',
          '필터 교체 주기 알림과 직관적 조작부'
        ],
        shipping: '내일(화) 새벽 도착 · 무료 설치',
        colorOptions: ['화이트', '차콜 블랙'],
        monthlyInstallment: '월 31,200원 (24개월, 무이자)',
        promoLabel: '필터 1회 무료 교체권 포함',
        image: '/balmuda-air-purifier.jpg'
      }
    ]
  },
  {
    id: 'living',
    name: '홈 & 리빙',
    tagline: '집 안을 호텔처럼, 감각적인 리빙 아이템',
    description: '따뜻한 무드 조명부터 프리미엄 커피·키친 웨어까지, 하루를 채우는 라이프스타일 큐레이션.',
    accent: '#f97316',
    heroImage: 'https://images.unsplash.com/photo-1505691938895-1758d7feb511?auto=format&fit=crop&w=1600&q=80',
    subheading: '감각적인 홈카페 · 인테리어 소품으로 일상 업그레이드',
    products: [
      {
        id: 'bruno-airfryer',
        name: 'BRUNO 프리미엄 에어프라이어 오븐 16L',
        brand: 'BRUNO',
        price: 289000,
        originalPrice: 329000,
        rating: 4.7,
        reviews: 952,
        badges: ['로켓와우 추가할인', '쿠폰 10%', 'MD 추천'],
        shortDescription: '16L 대용량과 9가지 자동 메뉴, 감각적인 컬러로 주방을 채우는 브루노 대표 제품.',
        highlights: [
          '360도 공기순환으로 바삭하게 익히는 대용량 조리',
          '9가지 자동메뉴와 손쉬운 다이얼 조작',
          '강화유리 도어와 세련된 파스텔 컬러 디자인',
          '탈착식 오븐 트레이로 손쉬운 세척'
        ],
        shipping: '오늘 주문 시 새벽 도착 · 무료 배송',
        colorOptions: ['크림 아이보리', '세이지 민트', '모던 블랙'],
        monthlyInstallment: '월 12,000원 (24개월, 무이자)',
        promoLabel: '전용 쿠킹 가이드북 동봉',
        image: '/bruno-airfryer.jpg'
      },
      {
        id: 'breville-barista-pro',
        name: 'Breville 바리스타 프로 BES878',
        brand: 'Breville',
        price: 1090000,
        originalPrice: 1190000,
        rating: 4.8,
        reviews: 255,
        badges: ['무료 바리스타 교육', '카드 청구할인', '로켓배송'],
        shortDescription: '홈카페의 완성, 정확한 온도 제어와 분쇄를 갖춘 브레빌의 시그니처 반자동 에스프레소 머신.',
        highlights: [
          'ThermoJet 시스템으로 3초 예열, 즉시 추출',
          '통합 버 그라인더가 30단계 입자 조절 지원',
          'LCD 디스플레이로 추출 시간과 온도 확인',
          '스테인리스 스팀 완드로 라떼 아트 가능'
        ],
        shipping: '내일(화) 새벽 도착 · 프리미엄 포장',
        colorOptions: ['브러시드 스테인리스', '블랙 트러플'],
        monthlyInstallment: '월 45,400원 (24개월, 무이자)',
        promoLabel: '원두 2종 + 템퍼 세트 증정',
        image: '/breville-barista-pro.jpg'
      },
      {
        id: 'atelier-modular-sofa',
        name: 'Atelier 모듈 소파 4인용',
        brand: 'Atelier Lounge',
        price: 1590000,
        originalPrice: 1790000,
        rating: 4.7,
        reviews: 532,
        badges: ['쇼룸 설치', '로켓와우 전용 혜택', '프리미엄 패브릭'],
        shortDescription: '깊은 좌방석과 모듈형 구조로 거실 공간을 자유롭게 연출할 수 있는 프리미엄 패브릭 소파.',
        highlights: [
          '분리형 모듈 구성으로 L자·일자형 자유 배치',
          '생활 방수 및 이지케어 기능성 패브릭 적용',
          '하이백 헤드레스트와 35kg/m³ 고밀도 쿠션',
          '전문 시공 팀이 배송·설치·폐가구 수거까지 지원'
        ],
        shipping: '희망일 지정 배송 · 설치 전문가 방문',
        colorOptions: ['아이보리 린넨', '그레이 트윌', '샌드 베이지'],
        monthlyInstallment: '월 66,300원 (24개월, 무이자)',
        promoLabel: '패브릭 보호 코팅 서비스 무상 제공',
        image: 'https://images.unsplash.com/photo-1586023492125-27b2c045efd7?auto=format&fit=crop&w=800&q=80'
      }
    ]
  }
];

const INITIAL_PRODUCT = CATEGORIES[0].products[0];

const currencyFormatter = new Intl.NumberFormat('ko-KR', {
  style: 'currency',
  currency: 'KRW'
});

const FALLBACK_IMAGE =
  'data:image/svg+xml;utf8,' +
  encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600" viewBox="0 0 800 600"><defs><linearGradient id="g" x1="0%" y1="0%" x2="100%" y2="100%"><stop offset="0%" stop-color="#dbeafe"/><stop offset="100%" stop-color="#a5b4fc"/></linearGradient></defs><rect width="800" height="600" rx="32" fill="url(#g)"/><g fill="#1e293b" font-family="Pretendard, Arial, sans-serif" text-anchor="middle"><text x="400" y="270" font-size="48" font-weight="700">Premium Product</text><text x="400" y="340" font-size="24" opacity="0.75">이미지를 불러오지 못했습니다</text></g></svg>`
  );

const handleImageFallback = (event) => {
  if (event.target.dataset.fallbackApplied === 'true') {
    return;
  }
  event.target.dataset.fallbackApplied = 'true';
  event.target.src = FALLBACK_IMAGE;
};

function randomKey() {
  return `mock-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function formatNumber(value) {
  return value.toLocaleString('ko-KR');
}

function formatDateTime(value) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  return date.toLocaleString('ko-KR', { hour12: false });
}

export default function App() {
  const [selectedCategoryId, setSelectedCategoryId] = useState(CATEGORIES[0].id);
  const [selectedProductId, setSelectedProductId] = useState(INITIAL_PRODUCT.id);
  const [selectedColor, setSelectedColor] = useState(INITIAL_PRODUCT.colorOptions[0]);
  const [quantity, setQuantity] = useState(1);
  const [receipt, setReceipt] = useState(null);
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(false);
  const [idempotencyKey, setIdempotencyKey] = useState(randomKey());
  const [manualPaymentId, setManualPaymentId] = useState('');
  const [lastPaymentId, setLastPaymentId] = useState(null);
  const [refundReason, setRefundReason] = useState('');
  const [sessionDetails, setSessionDetails] = useState(null);

  const selectedCategory = useMemo(
    () => CATEGORIES.find((category) => category.id === selectedCategoryId) ?? CATEGORIES[0],
    [selectedCategoryId]
  );

  useEffect(() => {
    const defaultProduct = selectedCategory.products[0];
    setSelectedProductId(defaultProduct.id);
  }, [selectedCategory]);

  const selectedProduct = useMemo(() => {
    return (
      selectedCategory.products.find((product) => product.id === selectedProductId) ??
      selectedCategory.products[0]
    );
  }, [selectedCategory, selectedProductId]);

  useEffect(() => {
    if (!selectedProduct) {
      return;
    }
    setSelectedColor((previous) =>
      selectedProduct.colorOptions.includes(previous) ? previous : selectedProduct.colorOptions[0]
    );
    setQuantity(1);
  }, [selectedProduct]);

  const totalAmount = selectedProduct.price * quantity;

  const handleApiError = (error, fallbackMessage) => {
    if (error?.response) {
      setStatus({
        type: 'error',
        message: error.response.data?.message ?? fallbackMessage
      });
    } else {
      setStatus({
        type: 'error',
        message: fallbackMessage
      });
    }
  };

  const handleAuthorizeOnly = async () => {
    setLoading(true);
    setStatus(null);

    try {
      const merchantId = 'MOCK-MERCHANT';
      const details = {
        productId: selectedProduct.id,
        productName: selectedProduct.name,
        color: selectedColor,
        quantity,
        unitPrice: selectedProduct.price,
        totalAmount
      };

      const authorizeResponse = await api.post('/payments/authorize', {
        merchantId,
        amount: totalAmount,
        currency: 'KRW',
        idempotencyKey
      });

      const paymentId = authorizeResponse.data.paymentId;
      setLastPaymentId(paymentId ?? null);
      setManualPaymentId(paymentId ? String(paymentId) : '');
      setSessionDetails(details);
      setReceipt({
        order: details,
        authorization: authorizeResponse.data,
        capture: null,
        refund: null
      });
      setStatus({
        type: 'success',
        message: '승인이 완료되었습니다. 동일 키로 재시도 시 멱등 응답이 반환됩니다.'
      });
    } catch (error) {
      handleApiError(error, '승인 요청 중 문제가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const resolvePaymentId = () => {
    const candidate =
      manualPaymentId.trim() || (lastPaymentId != null ? String(lastPaymentId) : '');
    if (!candidate) {
      setStatus({
        type: 'error',
        message: '결제 ID를 입력하거나 먼저 승인을 진행해주세요.'
      });
      return null;
    }
    if (!/^\d+$/.test(candidate)) {
      setStatus({
        type: 'error',
        message: '결제 ID는 숫자만 입력할 수 있습니다.'
      });
      return null;
    }
    return candidate;
  };

  const handleCapture = async () => {
    const paymentId = resolvePaymentId();
    if (!paymentId) {
      return;
    }

    setLoading(true);
    setStatus(null);

    try {
      const merchantId = 'MOCK-MERCHANT';
      const captureResponse = await api.post(`/payments/capture/${paymentId}`, {
        merchantId
      });

      const details =
        sessionDetails ??
        receipt?.order ?? {
          productId: selectedProduct.id,
          productName: selectedProduct.name,
          color: selectedColor,
          quantity,
          unitPrice: selectedProduct.price,
          totalAmount
        };

      setSessionDetails(details);
      setLastPaymentId(captureResponse.data.paymentId ?? Number(paymentId));
      setManualPaymentId(paymentId);
      setReceipt((previous) => ({
        order: details,
        authorization: previous?.authorization ?? null,
        capture: captureResponse.data,
        refund: null
      }));
      setStatus({
        type: 'success',
        message: '정산이 성공적으로 완료되었습니다.'
      });
    } catch (error) {
      handleApiError(error, '정산 요청 중 문제가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleRefund = async () => {
    const paymentId = resolvePaymentId();
    if (!paymentId) {
      return;
    }

    setLoading(true);
    setStatus(null);

    try {
      const merchantId = 'MOCK-MERCHANT';
      const refundResponse = await api.post(`/payments/refund/${paymentId}`, {
        merchantId,
        reason: refundReason.trim() ? refundReason.trim() : 'manual-refund'
      });

      const details =
        sessionDetails ??
        receipt?.order ?? {
          productId: selectedProduct.id,
          productName: selectedProduct.name,
          color: selectedColor,
          quantity,
          unitPrice: selectedProduct.price,
          totalAmount
        };

      setSessionDetails(details);
      setLastPaymentId(refundResponse.data.paymentId ?? Number(paymentId));
      setManualPaymentId(paymentId);
      setReceipt((previous) => ({
        order: details,
        authorization: previous?.authorization ?? null,
        capture: previous?.capture ?? null,
        refund: refundResponse.data
      }));
      setStatus({
        type: 'success',
        message: '환불이 완료되었습니다.'
      });
    } catch (error) {
      handleApiError(error, '환불 요청 중 문제가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handlePurchase = async () => {
    setLoading(true);
    setStatus(null);

    try {
      const merchantId = 'MOCK-MERCHANT';
      const keyToUse = idempotencyKey;
      const details = {
        productId: selectedProduct.id,
        productName: selectedProduct.name,
        color: selectedColor,
        quantity,
        unitPrice: selectedProduct.price,
        totalAmount
      };

      const authorizeResponse = await api.post('/payments/authorize', {
        merchantId,
        amount: totalAmount,
        currency: 'KRW',
        idempotencyKey: keyToUse
      });

      const paymentId = authorizeResponse.data.paymentId;
      const captureResponse = await api.post(`/payments/capture/${paymentId}`, {
        merchantId
      });

      setSessionDetails(details);
      setLastPaymentId(paymentId ?? null);
      setManualPaymentId(paymentId ? String(paymentId) : '');
      setReceipt({
        order: details,
        authorization: authorizeResponse.data,
        capture: captureResponse.data,
        refund: null
      });
      setStatus({
        type: 'success',
        message: '승인과 정산이 연속으로 완료되었습니다.'
      });
      setIdempotencyKey(randomKey());
    } catch (error) {
      handleApiError(error, '승인 및 정산 처리 중 문제가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    setIdempotencyKey(randomKey());
    setManualPaymentId('');
    setLastPaymentId(null);
    setRefundReason('');
    setSessionDetails(null);
    setReceipt(null);
    setStatus(null);
    setQuantity(1);
    setSelectedColor(selectedProduct.colorOptions[0]);
  };

  return (
    <div className="marketplace-app">
      <header className="marketplace-header">
        <div className="brand">
          <span className="brand__badge">PAYMENT SWELITE</span>
          <h1>프리미엄 마켓</h1>
          <p>오늘 주문하면 내일 도착하는 하이엔드 쇼핑 경험</p>
        </div>
        <div className="search-bar">
          <input
            type="text"
            placeholder="찾으시는 상품명을 입력해보세요 (예: 아이폰 16, 로봇청소기, 바리스타 머신)"
            disabled
          />
          <button type="button" disabled>
            검색 준비 중
          </button>
        </div>
        <dl className="summary">
          <div>
            <dt>최근 결제 ID</dt>
            <dd>{lastPaymentId ?? '-'}</dd>
          </div>
          <div>
            <dt>선택한 상품</dt>
            <dd>{selectedProduct.name}</dd>
          </div>
          <div>
            <dt>총 결제 금액</dt>
            <dd>{currencyFormatter.format(totalAmount)}</dd>
          </div>
        </dl>
      </header>

      <CategoryTabs
        categories={CATEGORIES}
        selectedId={selectedCategoryId}
        onSelect={setSelectedCategoryId}
      />

      <section
        className="hero-banner"
        style={{
          '--accent': selectedCategory.accent
        }}
      >
        <div className="hero-banner__copy">
          <h2>{selectedCategory.tagline}</h2>
          <p>{selectedCategory.description}</p>
          <ul>
            <li>카드사 제휴 할인 · 무이자 할부 혜택</li>
            <li>실시간 재고 연동 · 주문 상태 추적</li>
            <li>멱등 키를 활용한 안전한 결제 시나리오 실험</li>
          </ul>
        </div>
        <div className="hero-banner__visual">
          <img src={selectedCategory.heroImage} alt={selectedCategory.name} onError={handleImageFallback} />
        </div>
      </section>

      <section className="catalog-section">
        <header className="catalog-section__header">
          <div>
            <h2>{selectedCategory.name} 추천 상품</h2>
            <p>{selectedCategory.subheading}</p>
          </div>
          <span className="catalog-section__badge">총 {formatNumber(selectedCategory.products.length)}개 상품</span>
        </header>

        <div className="catalog-grid">
          {selectedCategory.products.map((product) => (
            <ProductCard
              key={product.id}
              product={product}
              active={product.id === selectedProduct.id}
              onSelect={() => setSelectedProductId(product.id)}
            />
          ))}
        </div>
      </section>

      <section className="detail-layout">
        <ProductDetailPanel
          product={selectedProduct}
          selectedColor={selectedColor}
          onColorChange={setSelectedColor}
          quantity={quantity}
          onQuantityChange={setQuantity}
          totalAmount={totalAmount}
        />

        <aside className="side-panel">
          <OrderControlPanel
            idempotencyKey={idempotencyKey}
            onIdempotencyKeyChange={setIdempotencyKey}
            onRandomizeKey={() => setIdempotencyKey(randomKey())}
            manualPaymentId={manualPaymentId}
            onManualPaymentIdChange={setManualPaymentId}
            refundReason={refundReason}
            onRefundReasonChange={setRefundReason}
            onAuthorize={handleAuthorizeOnly}
            onCapture={handleCapture}
            onRefund={handleRefund}
            onPurchase={handlePurchase}
            onReset={handleReset}
            loading={loading}
            status={status}
            lastPaymentId={lastPaymentId}
            selectedProduct={selectedProduct}
            selectedColor={selectedColor}
            quantity={quantity}
            totalAmount={totalAmount}
          />

          <ReceiptPanel receipt={receipt} />
        </aside>
      </section>
    </div>
  );
}

function CategoryTabs({ categories, selectedId, onSelect }) {
  return (
    <nav className="category-tabs">
      {categories.map((category) => (
        <button
          key={category.id}
          type="button"
          className={category.id === selectedId ? 'active' : ''}
          onClick={() => onSelect(category.id)}
        >
          <span>{category.name}</span>
          <small>{category.tagline}</small>
        </button>
      ))}
    </nav>
  );
}

function ProductCard({ product, active, onSelect }) {
  return (
    <article
      className={active ? 'product-card is-active' : 'product-card'}
      onClick={onSelect}
      role="button"
      tabIndex={0}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onSelect();
        }
      }}
    >
      <div className="product-card__media">
        <img src={product.image} alt={product.name} onError={handleImageFallback} />
        <span className="product-card__promo">{product.promoLabel}</span>
      </div>
      <div className="product-card__body">
        <div className="product-card__brand">{product.brand}</div>
        <h3>{product.name}</h3>
        <p className="product-card__description">{product.shortDescription}</p>
        <div className="product-card__rating">
          <strong>{product.rating.toFixed(1)}</strong>
          <span>리뷰 {formatNumber(product.reviews)}</span>
        </div>
        <div className="product-card__price">
          <strong>{currencyFormatter.format(product.price)}</strong>
          {product.originalPrice && (
            <span className="product-card__original">{currencyFormatter.format(product.originalPrice)}</span>
          )}
        </div>
        <div className="product-card__installment">{product.monthlyInstallment}</div>
        <div className="product-card__badges">
          {product.badges.map((badge) => (
            <span key={badge}>{badge}</span>
          ))}
        </div>
      </div>
    </article>
  );
}

function ProductDetailPanel({ product, selectedColor, onColorChange, quantity, onQuantityChange, totalAmount }) {
  return (
    <section className="detail-panel">
      <div className="detail-panel__visual">
        <img src={product.image} alt={product.name} onError={handleImageFallback} />
        <span className="detail-panel__shipping">{product.shipping}</span>
      </div>
      <div className="detail-panel__content">
        <div className="detail-panel__header">
          <span className="detail-panel__brand">{product.brand}</span>
          <h2>{product.name}</h2>
          <p>{product.shortDescription}</p>
        </div>

        <div className="detail-panel__pricing">
          <div className="detail-panel__price">
            <strong>{currencyFormatter.format(product.price)}</strong>
            {product.originalPrice && (
              <span>{currencyFormatter.format(product.originalPrice)}</span>
            )}
          </div>
          <div className="detail-panel__installment">{product.monthlyInstallment}</div>
        </div>

        <div className="detail-panel__meta">
          <div>
            <span className="label">색상 선택</span>
            <select value={selectedColor} onChange={(event) => onColorChange(event.target.value)}>
              {product.colorOptions.map((color) => (
                <option key={color} value={color}>
                  {color}
                </option>
              ))}
            </select>
          </div>

          <div>
            <span className="label">수량</span>
            <input
              type="number"
              min={1}
              max={5}
              value={quantity}
              onChange={(event) => {
                const value = Number(event.target.value);
                onQuantityChange(Number.isNaN(value) ? 1 : Math.min(Math.max(value, 1), 5));
              }}
            />
          </div>

          <div>
            <span className="label">결제 예상 금액</span>
            <strong>{currencyFormatter.format(totalAmount)}</strong>
          </div>
        </div>

        <ul className="detail-panel__highlights">
          {product.highlights.map((highlight) => (
            <li key={highlight}>{highlight}</li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function OrderControlPanel({
  idempotencyKey,
  onIdempotencyKeyChange,
  onRandomizeKey,
  manualPaymentId,
  onManualPaymentIdChange,
  refundReason,
  onRefundReasonChange,
  onAuthorize,
  onCapture,
  onRefund,
  onPurchase,
  onReset,
  loading,
  status,
  lastPaymentId,
  selectedProduct,
  selectedColor,
  quantity,
  totalAmount
}) {
  return (
    <section className="order-panel">
      <header>
        <h3>주문 & 결제 제어판</h3>
        <p>멱등 키를 활용해 승인·정산·환불 시나리오를 단계별로 실험해보세요.</p>
      </header>

      <div className="order-panel__summary">
        <div>
          <span className="label">선택한 상품</span>
          <strong>{selectedProduct.name}</strong>
          <span>{selectedColor} · {quantity}개</span>
        </div>
        <div>
          <span className="label">총 결제 금액</span>
          <strong>{currencyFormatter.format(totalAmount)}</strong>
          <span>최근 결제 ID: {lastPaymentId ?? '-'}</span>
        </div>
      </div>

      <div className="order-panel__group">
        <label>
          <span>멱등 키</span>
          <div className="field-with-action">
            <input
              type="text"
              value={idempotencyKey}
              onChange={(event) => onIdempotencyKeyChange(event.target.value)}
              disabled={loading}
            />
            <button type="button" onClick={onRandomizeKey} disabled={loading}>
              새 키 생성
            </button>
          </div>
        </label>

        <label>
          <span>결제 ID</span>
          <input
            type="text"
            value={manualPaymentId}
            onChange={(event) => onManualPaymentIdChange(event.target.value)}
            placeholder="예: 1"
            disabled={loading}
          />
        </label>

        <label>
          <span>환불 사유</span>
          <input
            type="text"
            value={refundReason}
            onChange={(event) => onRefundReasonChange(event.target.value)}
            placeholder="환불 사유를 입력해주세요"
            disabled={loading}
          />
        </label>
      </div>

      <div className="order-panel__actions">
        <button type="button" onClick={onAuthorize} disabled={loading}>
          승인만 요청
        </button>
        <button type="button" onClick={onCapture} disabled={loading}>
          정산 요청
        </button>
        <button type="button" onClick={onRefund} disabled={loading}>
          환불 요청
        </button>
        <button type="button" className="primary" onClick={onPurchase} disabled={loading}>
          즉시 구매 (승인→정산)
        </button>
      </div>

      <div className="order-panel__footer">
        <button type="button" onClick={onReset} disabled={loading}>
          화면 초기화
        </button>
        <span>요청 시 백엔드 Redis/DB/Kafka 상태를 함께 확인해보세요.</span>
      </div>

      {status && (
        <div className={status.type === 'success' ? 'status-banner success' : 'status-banner error'}>
          {status.message}
        </div>
      )}
    </section>
  );
}

function ReceiptPanel({ receipt }) {
  if (!receipt) {
    return (
      <section className="receipt-panel empty">
        <h3>주문 타임라인</h3>
        <p>승인 또는 정산을 진행하면 여기에 상세한 타임라인이 표시됩니다.</p>
      </section>
    );
  }

  const { order, authorization, capture, refund } = receipt;

  return (
    <section className="receipt-panel">
      <h3>주문 타임라인</h3>

      <div className="receipt-panel__order">
        <div>
          <span className="label">상품</span>
          <strong>{order.productName}</strong>
          <span>{order.color} · {order.quantity}개</span>
        </div>
        <div>
          <span className="label">결제 금액</span>
          <strong>{currencyFormatter.format(order.totalAmount)}</strong>
        </div>
      </div>

      <div className="receipt-panel__steps">
        {authorization && (
          <div className="receipt-panel__step">
            <header>
              <span className="step-badge">Authorize</span>
              <time dateTime={authorization.createdAt}>{formatDateTime(authorization.createdAt)}</time>
            </header>
            <dl>
              <div>
                <dt>결제 상태</dt>
                <dd>{authorization.status}</dd>
              </div>
              <div>
                <dt>메시지</dt>
                <dd>{authorization.message}</dd>
              </div>
              <div>
                <dt>결제 ID</dt>
                <dd>{authorization.paymentId}</dd>
              </div>
            </dl>
          </div>
        )}

        {capture && (
          <div className="receipt-panel__step">
            <header>
              <span className="step-badge capture">Capture</span>
              <time dateTime={capture.createdAt}>{formatDateTime(capture.createdAt)}</time>
            </header>
            <dl>
              <div>
                <dt>결제 상태</dt>
                <dd>{capture.status}</dd>
              </div>
              <div>
                <dt>메시지</dt>
                <dd>{capture.message}</dd>
              </div>
              <div>
                <dt>장부 기록</dt>
                <dd>
                  {capture.ledgerEntries?.map((entry) => `${entry.debitAccount} → ${entry.creditAccount}`).join(', ') ??
                    '없음'}
                </dd>
              </div>
            </dl>
          </div>
        )}

        {refund && (
          <div className="receipt-panel__step">
            <header>
              <span className="step-badge refund">Refund</span>
              <time dateTime={refund.createdAt}>{formatDateTime(refund.createdAt)}</time>
            </header>
            <dl>
              <div>
                <dt>결제 상태</dt>
                <dd>{refund.status}</dd>
              </div>
              <div>
                <dt>메시지</dt>
                <dd>{refund.message}</dd>
              </div>
              <div>
                <dt>장부 기록</dt>
                <dd>
                  {refund.ledgerEntries?.map((entry) => `${entry.debitAccount} → ${entry.creditAccount}`).join(', ') ??
                    '없음'}
                </dd>
              </div>
            </dl>
          </div>
        )}
      </div>
    </section>
  );
}
