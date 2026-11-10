// =========================================================================
// I18N (RU / EN LOCALIZATION + REDDIT SCREENSHOT ADDRESS MASKING) & FORMATTERS
// =========================================================================

let LANG = localStorage.getItem('iskra_lang') || 'ru';
function isEn() { return LANG === 'en'; }

const STATIC_EN_MAP = {
  'ЗАРЯД СТАНЦИИ': 'STATION BATTERY SOC',
  'ВХОД / ВЫХОД': 'POWER IN / OUT',
  'СЕТЬ ДТЕК (SOCKET-1)': 'DTEK GRID (SOCKET-1)',
  'ТЕКУЩАЯ ФАЗА': 'ACTIVE CYCLE PHASE',
  'УПРАВЛЕНИЕ ЗАРЯДКОЙ И ЛИНИЯМИ ПИТАНИЯ': 'CHARGING PROFILE & INPUT ROUTING',
  'ГЕНЕРАТОР (SOCKET-2)': 'GENERATOR (SOCKET-2)',
  'ПРОФИЛЬ МОЩНОСТИ ЗАРЯДКИ (OUKITEL P1000E+)': 'AC CHARGING POWER PROFILE (OUKITEL P1000E+)',
  '1. ПРОГРЕВ (25%)': '1. WARMUP (25%)',
  'Первые 10-15 мин (300W в бат.)': 'First 10-15m (300W to batt)',
  '2. ОТ ГЕНЕРАТОРА (50%)': '2. GENERATOR (50%)',
  'Рабочий с ПК (600W в бат.)': 'Standard w/ PC (600W to batt)',
  '3. ОТ СЕТИ (100%)': '3. GRID FAST (100%)',
  'Максимум ДТЕК (1200W в бат.)': 'Max 100% (1200W fast charge)',
  'ВЫХОДЫ СТАНЦИИ OUKITEL': 'OUKITEL OUTPUT BUSES',
  'LED ФОНАРЬ': 'LED LIGHT',
  'ГЕНЕРАТОР RATO & РЕЛЕ ГЛУШЕНИЯ SHELLY': 'RATO R3000 GENSET & SHELLY KILL RELAY',
  'СТАТУС': 'STATUS',
  'ТЕКУЩИЙ ПУСК': 'RUN TIME',
  'ТЕМП. РЕЛЕ': 'RELAY TEMP',
  'МОТОЧАСЫ': 'ENGINE HRS',
  'ОТМЕНИТЬ СТОП': 'CANCEL STOP',
  'ЗАГЛУШИТЬ СРАЗУ': 'KILL IMMEDIATELY',
  'ПРОДУВКА ПЕРЕД СТОПОМ:': 'IDLE COOLDOWN TIMER:',
  '15с': '15s',
  '20с': '20s',
  '30с': '30s',
  '45с': '45s',
  '60с': '60s',
  'УМНЫЙ СТОП RATO (2 ФАЗЫ)': 'SMART 2-STAGE STOP',
  'СТОП СРАЗУ': 'KILL NOW',
  'Удержание до стопа': 'Hold Until Dead Stop',
  'Прямой импульс': 'Instant Relay Pulse',
  'ВЫБОР ПЕРИОДА АНАЛИТИКИ': 'ANALYTICS TIME RANGE',
  '12Ч': '12H',
  '24Ч': '24H',
  'ДЕНЬ': 'TODAY',
  'СУТКИ 24Ч': '24 HOURS',
  'НЕДЕЛЯ': '7 DAYS',
  'МЕСЯЦ': '30 DAYS',
  'ПЕРИОД': 'CUSTOM',
  'НАЧАЛО ПЕРИОДА:': 'START DATE:',
  'КОНЕЦ ПЕРИОДА:': 'END DATE:',
  'СВОДКА ВРЕМЕНИ ЗА ПЕРИОД (ТОЧНОСТЬ ДО МИНУТЫ)': 'POWER SOURCE BREAKDOWN (MINUTE-EXACT)',
  'СБРОС ЛОГА': 'RESET LOG',
  'ОТ СЕТИ (ДТЕК)': 'DTEK UTILITY GRID',
  'ОТ ГЕНЕРАТОРА (RATO)': 'RATO R3000 GENSET',
  'БЕЗ СЕТИ И ГЕНЕРАТОРА': 'OUKITEL BATTERY',
  'ВСЕ СЕЛО В 0 / ОФФЛАЙН': 'FULL BLACKOUT (0%)',
  'ГРАФИК ИСТОЧНИКОВ: СЕТЬ ДТЕК / ГЕНЕРАТОР RATO / СТАНЦИЯ': 'POWER SOURCE TIMELINE: GRID / GENERATOR / BATTERY',
  'ДО МИНУТЫ': '1-MIN RES',
  'ХРОНОЛОГИЯ ПЕРЕКЛЮЧЕНИЙ (ТОЧНО ДО МИНУТЫ)': 'SOURCE SWITCHING LOG (MINUTE-EXACT)',
  'МОЩНОСТЬ: ВХОД, ЗАРЯД БАТАРЕИ И НАГРУЗКА ДОМА (ВТ)': 'POWER TELEMETRY: INPUT, BATTERY CHARGE & HOME LOAD (W)',
  'ЗАРЯД СТАНЦИИ OUKITEL P1000E+ (0% — 100%)': 'OUKITEL P1000E+ STATE OF CHARGE (0% — 100%)',
  'ЕМКОСТЬ 1024 Вт·ч': 'CAPACITY 1024 Wh',
  'Линия': 'Series',
  'Параметр': 'Metric',
  'Сейчас': 'Current',
  'Мин': 'Min',
  'Макс': 'Max',
  'Среднее': 'Avg',
  'ОБНОВИТЬ': 'REFRESH',
  'SNEK_POWER_STRIP // ТЕРРАРИУМ И РАБОЧЕЕ МЕСТО': 'SNEK_POWER_STRIP // BOA TERRARIUM & WORKSTATION',
  'ОБОГРЕВ ЗМЕИ (SW2)': 'BOA HEAT LAMP (SW2)',
  'СВЕТ ЗМЕИ (SW1)': 'BOA DAYLIGHT (SW1)',
  'WI-FI ЛАМПА (SW3)': 'AMBIENT LAMP (SW3)',
  'НОУТБУК (SW4)': 'LEGION LAPTOP (SW4)',
  'БЕЗОПАСНОСТЬ И КЛИМАТ (CO + AIRBOX)': 'BUNKER SAFETY & CLIMATE (CO + AIRBOX)',
  'ДАТЧИКИ БУНКЕРА': 'BUNKER SENSORS',
  'УГАРНЫЙ ГАЗ': 'CARBON MONOXIDE',
  'БАТАРЕЯ CO': 'CO SENSOR BATT',
  'ВЛАЖНОСТЬ': 'HUMIDITY',
  'CO2 В КОМНАТЕ': 'INDOOR CO2',
  'ОСВЕЩЕНИЕ И ОЧИСТИТЕЛЬ ВОЗДУХА': 'ROOM LIGHTING & AIR PURIFIER',
  'ПОДСВЕТКА': 'BACKLIGHT',
  'ЛАМПА W5K': 'MAIN BULB',
  'ОЧИСТИТЕЛЬ': 'AIR PURIFIER',
  'АВТОНОМНОСТЬ БЕЗ ИНТЕРНЕТА И ПРИ РАЗРЯДЕ В 0%': 'OFFLINE LAN & ZERO-BATTERY PERSISTENCE',
  'БАТАРЕЯ ОК': 'BATTERY OK',
  'РАСХОД БЕНЗИНА (В ЛИТРАХ ПО НАГРУЗКЕ)': 'GASOLINE CONSUMPTION (LOAD-WEIGHTED LITERS)',
  'СЕГОДНЯ (СУТКИ)': 'TODAY (24H)',
  'ВЧЕРА (СУТКИ)': 'YESTERDAY',
  'ЗА 7 ДНЕЙ': 'LAST 7 DAYS',
  'ЗА 30 ДНЕЙ / ВСЕГО': 'LAST 30D / TOTAL',
  'ПОСУТОЧНЫЙ ЖУРНАЛ СОЖЖЁННОГО БЕНЗИНА': 'DAILY GASOLINE BURN LOG',
  'ОБСЛУЖИВАНИЕ (МАСЛО И СВЕЧА ЗАЖИГАНИЯ)': 'ENGINE MAINTENANCE (OIL & SPARK PLUG)',
  'МАСЛО ЗАМЕНЕНО (0ч)': 'MARK OIL CHANGED (0h)',
  'ЗАБЫЛА ОТМЕТИТЬ / ЧАСЫ': 'SET CUSTOM HOURS',
  'СВЕЧА ЗАЖИГАНИЯ (F7TC / NGK BPR6ES)': 'SPARK PLUG (F7TC / NGK BPR6ES)',
  'СВЕЧА ЗАМЕНЕНА (0ч)': 'MARK PLUG REPLACED (0h)',
  'ЗАДАТЬ НАРАБОТКУ СВЕЧИ': 'SET PLUG HOURS',
  'ПАСПОРТ И НАСТРОЙКИ ГЕНЕРАТОРА': 'GENERATOR PASSPORT & PARAMETERS',
  'МОДЕЛЬ ГЕНЕРАТОРА / ДВИГАТЕЛЬ': 'GENERATOR MODEL / ENGINE',
  'НОМИНАЛЬНАЯ МОЩНОСТЬ (КВТ)': 'RATED POWER (KW)',
  'МАКСИМАЛЬНАЯ МОЩНОСТЬ (КВТ)': 'PEAK POWER (KW)',
  'РАСХОД ПРИ 100% НАГРУЗКИ (Л/Ч)': 'BURN RATE @ 100% LOAD (L/H)',
  'РАСХОД ПРИ ½ НАГРУЗКИ (Л/Ч)': 'BURN RATE @ 50% LOAD (L/H)',
  'ОБЪЁМ МАСЛА В КАРТЕРЕ (Л)': 'CRANKCASE OIL VOLUME (L)',
  'ОБЪЁМ ТОПЛИВНОГО БАКА (Л)': 'FUEL TANK CAPACITY (L)',
  'ИНТЕРВАЛ ЗАМЕНЫ МАСЛА (Ч)': 'OIL CHANGE INTERVAL (HRS)',
  'ИНТЕРВАЛ ЗАМЕНЫ СВЕЧИ (Ч)': 'SPARK PLUG INTERVAL (HRS)',
  'ОБЩИЕ МОТОЧАСЫ ГЕНЕРАТОРА (ДЛЯ КОРРЕКТИРОВКИ, Ч)': 'TOTAL ENGINE HOURS OVERRIDE (HRS)',
  'СОХРАНИТЬ ПАРАМЕТРЫ ГЕНЕРАТОРА': 'SAVE GENERATOR PARAMETERS',
  'НАСТРОЙКА КНОПОК ШАГОВ ЗАРЯДКИ (%)': 'CUSTOM CHARGING STEP BUTTONS (%)',
  'ШАГ 1 (%)': 'STEP 1 (%)',
  'ШАГ 2 (%)': 'STEP 2 (%)',
  'ШАГ 3 (%)': 'STEP 3 (%)',
  'ШАГ 4 (%)': 'STEP 4 (%)',
  'ШАГ 5 (%)': 'STEP 5 (%)',
  'ПРЕСЕТ ПОД ИГРУ (45%)': 'GAMING PRESET (45%)',
  'ПРЕСЕТ БЕЗ ИГРЫ (50%)': 'STANDARD PRESET (50%)',
  'СОХРАНИТЬ ШАГИ КНОПОК': 'SAVE STEP BUTTONS',
  'МОДУЛЬ АВТОМАТИЗАЦИИ (SMART AUTOMATION)': 'SMART AUTOMATION MODULE',
  '1. МЯГКИЙ ФИНИШ БАТАРЕИ (БАЛАНСИРОВКА LiFePO4)': '1. LiFePO4 SOFT TOP-OFF (CELL BALANCING)',
  'Плавное снижение тока на верхушке заряда без гула кулеров': 'Drops charge power near full SoC for quiet cell balancing',
  'ПОРОГ СРАБАТЫВАНИЯ (%)': 'TRIGGER THRESHOLD (%)',
  'ЦЕЛЕВОЙ ШАГ ТОКА (%)': 'TARGET TOP-OFF LIMIT (%)',
  '2. ЗАЩИТА ПРОГРЕВА ГЕНЕРАТОРА RATO ПРИ СТАРТЕ': '2. COLD GENERATOR STARTUP WARMUP GUARD',
  'Если лимит >75%, при пуске Rato ставит 25% (не трогает ручной выбор)': 'If left >75%, sets 25% on Rato start (never overrides manual)',
  '3. АВТО-РАЗГОН 100% ПРИ ВКЛЮЧЕНИИ СЕТИ ДТЕК': '3. DTEK GRID RESTORE FAST-CHARGE BOOST (100%)',
  'Когда дали свет и генератор заглушен — ставит 100% (1200W)': 'When utility grid returns and genset is off — sets 100% (1200W)',
  '4. СИГНАЛ «ПОРА ГЛУШИТЬ RATO» (ЭКОНОМИЯ БЕНЗИНА)': '4. GENERATOR TARGET SOC ALERT (FUEL SAVER)',
  'Подсказка на пульте, когда станция зарядилась от генератора': 'Alerts on dashboard when battery reaches target SoC on genset',
  'ПОРОГ СИГНАЛА НА ГЕНЕРАТОРЕ (%)': 'GENERATOR ALERT SOC (%)',
  'СОХРАНИТЬ НАСТРОЙКИ АВТОМАТИЗАЦИИ': 'SAVE AUTOMATION RULES',
  'РАСХОД БЕНЗИНА RATO (ГРАФИК И СВОДКА ЗА ПЕРИОД)': 'RATO GASOLINE CONSUMPTION (CHART & PERIOD SUMMARY)',
  'СЕГОДНЯ (С 00:00)': 'TODAY (SINCE 00:00)',
  'С ПОЛНОГО БАКА': 'SINCE FULL TANK',
  'Бензин Rato': 'Rato Fuel',
  'Последнее событие:': 'Last event:',
  '⛽ ТОЧКА ОТСЧЁТА: ПОЛНЫЙ БАК (14.5 Л)': '⛽ CHECKPOINT: FULL TANK (14.5 L)',
  'Я ЗАЛИЛА ПОЛНЫЙ БАК': 'MARK TANK FULL (14.5L)',
  'Сбросить отсчёт бака в 0м': 'Reset full-tank counter to 0m',
  'ПО ДАТЧИКУ ½ БАКА': 'CALIBRATE AT ½ TANK',
  'Калибровка (~7.25 л ушло)': 'Calibrate (~7.25 L burned)',
  'КАЛИБРОВКА РАСХОДА ПО ДОЛИВУ ИЗ КАНИСТРЫ': 'CALIBRATE BURN RATE BY JERRYCAN REFILL',
  'ФАКТИЧЕСКИ УШЛО (Л)': 'ACTUAL LITERS USED (L)',
  'ЗА СКОЛЬКО ЧАСОВ (Ч)': 'ENGINE RUN HOURS (H)',
  'ОТКАЛИБРОВАТЬ РАСХОД ПО ФАКТУ': 'CALIBRATE BURN RATE NOW'
};

const STATIC_RU_MAP = {};
Object.keys(STATIC_EN_MAP).forEach(k => { STATIC_RU_MAP[STATIC_EN_MAP[k]] = k; });

function setLang(lang) {
  LANG = lang === 'en' ? 'en' : 'ru';
  localStorage.setItem('iskra_lang', LANG);
  const bRu = document.getElementById('btnLangRu');
  const bEn = document.getElementById('btnLangEn');
  if (bRu) bRu.className = 'ctrl-btn ' + (LANG === 'ru' ? 'active-blue' : '');
  if (bEn) bEn.className = 'ctrl-btn ' + (LANG === 'en' ? 'active-blue' : '');

  const dict = LANG === 'en' ? STATIC_EN_MAP : STATIC_RU_MAP;
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
  let node;
  while ((node = walker.nextNode())) {
    const t = node.nodeValue ? node.nodeValue.trim() : '';
    if (t && dict[t]) {
      node.nodeValue = dict[t];
    }
  }

  const dtekGroupHdr = document.querySelector('#view-dtek .panel-title');
  if (dtekGroupHdr) {
    const grp = (window.S && S.dtekSchedule && S.dtekSchedule.group) ? S.dtekSchedule.group : '33.1';
    dtekGroupHdr.innerHTML = isEn()
      ? `DTEK KYIV GRID // GROUP <span id="dtGroupBadge" style="color:var(--cyan);">${grp}</span>`
      : `ДТЕК КЕМ // ГРУПА <span id="dtGroupBadge" style="color:var(--cyan);">${grp}</span>`;
  }
  const oilHdr = document.querySelector('#oilMaintCard span');
  if (oilHdr) {
    const v = document.getElementById('oilVolBadge') ? document.getElementById('oilVolBadge').textContent : '0.55';
    oilHdr.innerHTML = isEn()
      ? `CRANKCASE OIL (<span id="oilVolBadge">${v}</span> L • SAE 10W-30/40)`
      : `МАСЛО В КАРТЕРЕ (<span id="oilVolBadge">${v}</span> л • 10W-30/40)`;
  }
  const offlineBox = document.querySelector('#view-bunker .panel:last-child div div');
  if (offlineBox) {
    offlineBox.innerHTML = isEn()
      ? '• When WAN internet drops, Iskra controls Oukitel (TCP 6607) and Shelly (192.168.86.192) directly over local LAN.<br>• If battery hits 0% and router shuts down, blackout duration is persisted and stitched automatically on reboot.'
      : '• При падении интернета пульт управляет Oukitel (LAN 6607) и Shelly (192.168.86.192) напрямую по Wi-Fi.<br>• Если станция сядет в 0% и выключится роутер, время простоя фиксируется и автоматически сшивается при следующем запуске.';
  }
  if (typeof setCooldownSec === 'function') setCooldownSec(window.cooldownSec || 20);
  if (window.S && typeof renderAll === 'function') renderAll(window.S);
}

function fmtDur(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (isEn()) {
    if (d > 0) return `${d}d ${h}h ${m}m`;
    if (h > 0) return `${h}h ${String(m).padStart(2,'0')}m`;
    if (m > 0) return `${m}m`;
    return `${s}s`;
  }
  if (d > 0) return `${d}д ${h}ч ${m}м`;
  if (h > 0) return `${h}ч ${String(m).padStart(2,'0')}м`;
  if (m > 0) return `${m}м`;
  return `${s}с`;
}

function fmtMinExact(ms) {
  const totalMin = Math.max(0, Math.round(ms / 60000));
  const d = Math.floor(totalMin / 1440);
  const h = Math.floor((totalMin % 1440) / 60);
  const m = totalMin % 60;
  if (isEn()) {
    if (d > 0) return `${d}d ${h}h ${m}m`;
    if (h > 0) return `${h}h ${String(m).padStart(2,'0')}m`;
    return `${m} min`;
  }
  if (d > 0) return `${d}д ${h}ч ${m}м`;
  if (h > 0) return `${h}ч ${String(m).padStart(2,'0')}м`;
  return `${m} мин`;
}

function fmtMin(min) {
  min = Math.max(0, Math.floor(min || 0));
  const h = Math.floor(min / 60);
  const m = min % 60;
  if (isEn()) return h > 0 ? `${h}h ${String(m).padStart(2,'0')}m` : `${m}m`;
  return h > 0 ? `${h}ч ${String(m).padStart(2,'0')}м` : `${m}м`;
}

function fmtTimeHM(ms) {
  const dt = new Date(ms);
  return String(dt.getHours()).padStart(2,'0') + ':' + String(dt.getMinutes()).padStart(2,'0');
}

function fmtDateDM(ms) {
  const dt = new Date(ms);
  return String(dt.getDate()).padStart(2,'0') + '.' + String(dt.getMonth() + 1).padStart(2,'0');
}

function minToHM(m) {
  const mm = Math.max(0, Math.round(m || 0));
  if (mm >= 1440 && mm !== 1440) {
    const wrap = mm % 1440;
    return String(Math.floor(wrap / 60)).padStart(2, '0') + ':' + String(wrap % 60).padStart(2, '0');
  }
  const h = Math.floor(mm / 60);
  const r = mm % 60;
  return String(h).padStart(2, '0') + ':' + String(r).padStart(2, '0');
}

function fmtDurMin(m) {
  const mm = Math.max(0, Math.round(m || 0));
  const h = Math.floor(mm / 60);
  const r = mm % 60;
  if (isEn()) {
    if (h > 0 && r > 0) return `${h}h ${String(r).padStart(2, '0')}m`;
    if (h > 0) return `${h}h 00m`;
    return `${r}m`;
  }
  if (h > 0 && r > 0) return `${h}ч ${String(r).padStart(2, '0')}м`;
  if (h > 0) return `${h}ч 00м`;
  return `${r}м`;
}
