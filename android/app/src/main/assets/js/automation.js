// =========================================================================
// ISKRA MODULE: SMART AUTOMATION (LIFEPO4 SOFT TOP-OFF & EDGE TRIGGERS)
// =========================================================================

let autoConfigInitialized = false;
let autoStateCache = {
  softTopOffEnabled: true,
  softTopOffThresholdPct: 95,
  softTopOffTargetLimit: 25,
  coldGenGuardEnabled: true,
  coldGenSafeLimit: 25,
  gridRestoreBoostEnabled: false,
  gridRestoreTargetLimit: 100,
  genTargetSocAlertEnabled: true,
  genTargetSocPct: 90
};

function formatAutoEventText(raw) {
  if (!raw || raw === 'Ожидание событий автоматизации' || raw === 'Ожидание триггеров...') {
    return isEn() ? 'Waiting for automation triggers...' : 'Ожидание событий автоматизации';
  }
  if (!isEn()) return raw;
  return raw
    .replace('Старт Rato: лимит снижен с ', 'Rato Start: charge limit dropped ')
    .replace(' до ', ' -> ')
    .replace(' (прогрев)', ' (warmup)')
    .replace('Дали свет ДТЕК (заряд ', 'DTEK Grid restored (SoC ')
    .replace('): авто-разгон зарядки до ', '): auto-boost to ')
    .replace('Мягкий финиш (', 'Soft Top-Off (')
    .replace('): ток снижен с ', '): limit eased ')
    .replace(' для балансировки', ' for LiFePO4 balancing')
    .replace('Генератор Rato зарядил станцию до ', 'Rato charged station to ')
    .replace(' — можно глушить!', ' — ready to stop!');
}

function renderAutomationUI(d) {
  if (!d || !d.automation) return;
  const auto = d.automation;
  const cfg = auto.config || auto;

  if (!autoConfigInitialized) {
    autoConfigInitialized = true;
    autoStateCache = {
      softTopOffEnabled: cfg.softTopOffEnabled !== false,
      softTopOffThresholdPct: cfg.softTopOffThresholdPct ?? cfg.softTopOffSoc ?? 95,
      softTopOffTargetLimit: cfg.softTopOffTargetLimit ?? 25,
      coldGenGuardEnabled: (cfg.coldGenGuardEnabled ?? cfg.genWarmupGuardEnabled) !== false,
      coldGenSafeLimit: cfg.coldGenSafeLimit ?? 25,
      gridRestoreBoostEnabled: cfg.gridRestoreBoostEnabled !== false,
      gridRestoreTargetLimit: cfg.gridRestoreTargetLimit ?? 100,
      genTargetSocAlertEnabled: (cfg.genTargetSocAlertEnabled ?? cfg.genTargetAlertEnabled) !== false,
      genTargetSocPct: cfg.genTargetSocPct ?? cfg.genTargetSoc ?? 90
    };
    const setInp = (id, v) => {
      const el = document.getElementById(id);
      if (el) el.value = v;
    };
    setInp('inpAutoTopOffSoc', autoStateCache.softTopOffThresholdPct);
    setInp('inpAutoTopOffLim', autoStateCache.softTopOffTargetLimit);
    setInp('inpAutoColdLim', autoStateCache.coldGenSafeLimit);
    setInp('inpAutoGridLim', autoStateCache.gridRestoreTargetLimit);
    setInp('inpAutoGenAlertSoc', autoStateCache.genTargetSocPct);
  } else {
    autoStateCache.softTopOffEnabled = cfg.softTopOffEnabled !== false;
    autoStateCache.coldGenGuardEnabled = (cfg.coldGenGuardEnabled ?? cfg.genWarmupGuardEnabled) !== false;
    autoStateCache.gridRestoreBoostEnabled = cfg.gridRestoreBoostEnabled !== false;
    autoStateCache.genTargetSocAlertEnabled = (cfg.genTargetSocAlertEnabled ?? cfg.genTargetAlertEnabled) !== false;
  }

  // Update Live Event Banner on Tab 1 (Control Panel)
  const bannerTxt = document.getElementById('autoBannerText');
  const bannerMeta = document.getElementById('autoBannerMeta');
  const bannerBox = document.getElementById('autoEventBanner');
  const evText = formatAutoEventText(auto.lastEventText);
  if (bannerTxt && bannerMeta && bannerBox) {
    bannerTxt.textContent = evText;
    if (auto.softTopOffActive) {
      bannerBox.style.borderColor = 'rgba(51, 181, 229, 0.55)';
      bannerMeta.textContent = isEn() ? 'CELL BALANCING ACTIVE' : 'БАЛАНСИРОВКА ЯЧЕЕК';
      bannerMeta.style.color = 'var(--cyan)';
    } else if (auto.genReadyToStop) {
      bannerBox.style.borderColor = 'rgba(115, 191, 105, 0.65)';
      bannerMeta.textContent = isEn() ? 'READY TO STOP RATO' : 'МОЖНО ГЛУШИТЬ';
      bannerMeta.style.color = 'var(--green)';
    } else {
      bannerBox.style.borderColor = '#1C222D';
      bannerMeta.textContent = auto.lastEventMs > 0 ? fmtTimeHM(auto.lastEventMs) : 'AUTO';
      bannerMeta.style.color = 'var(--dim)';
    }
  }

  // Sync Toggle Buttons in Tab 5 (Automation Module Panel)
  const syncToggleBtn = (btnId, enabled, activeCls) => {
    const btn = document.getElementById(btnId);
    if (!btn) return;
    btn.className = 'ctrl-btn ' + (enabled ? activeCls : '');
    btn.style.minWidth = '66px';
    btn.style.textAlign = 'center';
    btn.style.padding = '5px 8px';
    btn.innerHTML = `<span style="font-size:10px;">${enabled ? (isEn() ? 'ON' : 'ВКЛ') : (isEn() ? 'OFF' : 'ВЫКЛ')}</span>`;
  };

  syncToggleBtn('btnAutoTopOff', autoStateCache.softTopOffEnabled, 'active-blue');
  syncToggleBtn('btnAutoColdGen', autoStateCache.coldGenGuardEnabled, 'active-amber');
  syncToggleBtn('btnAutoGridBoost', autoStateCache.gridRestoreBoostEnabled, 'active-green');
  syncToggleBtn('btnAutoGenAlert', autoStateCache.genTargetSocAlertEnabled, 'active-blue');

  const lastEvTab5 = document.getElementById('autoLastEventTab5');
  if (lastEvTab5) {
    lastEvTab5.textContent = evText;
  }
}

function toggleAutoRule(key) {
  autoStateCache[key] = !autoStateCache[key];
  saveAutomationSettings();
}

function saveAutomationSettings() {
  const getInt = (id, def, min, max) => {
    const el = document.getElementById(id);
    const v = parseInt(el ? el.value : String(def), 10);
    if (isNaN(v)) return def;
    return Math.max(min, Math.min(max, v));
  };

  const payload = {
    softTopOffEnabled: !!autoStateCache.softTopOffEnabled,
    softTopOffThresholdPct: getInt('inpAutoTopOffSoc', 95, 80, 99),
    softTopOffTargetLimit: getInt('inpAutoTopOffLim', 25, 0, 50),
    coldGenGuardEnabled: !!autoStateCache.coldGenGuardEnabled,
    coldGenSafeLimit: getInt('inpAutoColdLim', 25, 0, 50),
    gridRestoreBoostEnabled: !!autoStateCache.gridRestoreBoostEnabled,
    gridRestoreTargetLimit: getInt('inpAutoGridLim', 100, 25, 100),
    genTargetSocAlertEnabled: !!autoStateCache.genTargetSocAlertEnabled,
    genTargetSocPct: getInt('inpAutoGenAlertSoc', 90, 50, 100)
  };

  autoStateCache = { ...payload };

  if (window.IskraNative && window.IskraNative.saveAutomationConfig) {
    window.IskraNative.saveAutomationConfig(JSON.stringify(payload));
  }

  const metaEl = document.getElementById('autoSavedNotice');
  if (metaEl) {
    metaEl.textContent = isEn() ? '✓ SAVED' : '✓ СОХРАНЕНО';
    metaEl.style.color = 'var(--green)';
    setTimeout(() => {
      metaEl.textContent = 'LIFEPO4 BMS CARE';
      metaEl.style.color = 'var(--cyan)';
    }, 2000);
  }
  if (window.S) renderAutomationUI(window.S);
}
