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

function renderAutomationUI(d) {
  if (!d || !d.automation) return;
  const auto = d.automation;
  const cfg = auto.config || {};

  if (!autoConfigInitialized) {
    autoConfigInitialized = true;
    autoStateCache = {
      softTopOffEnabled: cfg.softTopOffEnabled !== false,
      softTopOffThresholdPct: cfg.softTopOffThresholdPct ?? 95,
      softTopOffTargetLimit: cfg.softTopOffTargetLimit ?? 25,
      coldGenGuardEnabled: cfg.coldGenGuardEnabled !== false,
      coldGenSafeLimit: cfg.coldGenSafeLimit ?? 25,
      gridRestoreBoostEnabled: !!cfg.gridRestoreBoostEnabled,
      gridRestoreTargetLimit: cfg.gridRestoreTargetLimit ?? 100,
      genTargetSocAlertEnabled: cfg.genTargetSocAlertEnabled !== false,
      genTargetSocPct: cfg.genTargetSocPct ?? 90
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
    autoStateCache.coldGenGuardEnabled = cfg.coldGenGuardEnabled !== false;
    autoStateCache.gridRestoreBoostEnabled = !!cfg.gridRestoreBoostEnabled;
    autoStateCache.genTargetSocAlertEnabled = cfg.genTargetSocAlertEnabled !== false;
  }

  // Update Live Event Banner on Tab 1 (Control Panel)
  const bannerTxt = document.getElementById('autoBannerText');
  const bannerMeta = document.getElementById('autoBannerMeta');
  const bannerBox = document.getElementById('autoEventBanner');
  if (bannerTxt && bannerMeta && bannerBox) {
    const evText = auto.lastEventText || (isEn() ? 'Waiting for triggers...' : 'Ожидание триггеров...');
    bannerTxt.textContent = evText;
    if (auto.softTopOffActive) {
      bannerBox.style.borderColor = 'rgba(51, 181, 229, 0.55)';
      bannerMeta.textContent = isEn() ? 'CELL BALANCING ACTIVE' : 'БАЛАНСИРОВКА ЯЧЕЕК';
      bannerMeta.style.color = 'var(--cyan)';
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
    const tStr = auto.lastEventMs > 0 ? ` (${fmtTimeHM(auto.lastEventMs)})` : '';
    lastEvTab5.textContent = `${auto.lastEventText || 'Ожидание триггеров...'}${tStr}`;
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
