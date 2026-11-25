/* ============================================================
   Raj AI Studio — Pro Studio Controller
   Multi-engine switcher, dual-mode aware, localStorage settings
   ============================================================ */
(() => {
  "use strict";

  const GATEWAY = "http://localhost:8080";
  const STORAGE_SETTINGS = "raj-ai-studio-settings";
  const STORAGE_HISTORY = "raj-ai-studio-history";
  const REALISM_SUFFIX = "photorealistic, ultra-detailed, 8k resolution, natural lighting, realistic textures, sharp focus, 35mm lens";

  const ENGINE_MODELS = {
    pollinations: [
      { value: "flux", label: "flux (Recommended)" },
      { value: "flux-realism", label: "flux-realism" },
      { value: "turbo", label: "turbo (Fast)" },
      { value: "zimage", label: "zimage" },
      { value: "any-dark", label: "any-dark" },
    ],
    huggingface: [
      { value: "black-forest-labs/FLUX.1-schnell", label: "FLUX.1-schnell (Fast)" },
      { value: "stabilityai/stable-diffusion-xl-base-1.0", label: "SDXL Base" },
    ],
  };

  const ENGINE_HINTS = {
    pollinations: "Zero setup. Best for instant, reliable generation.",
    huggingface: "Optional free Hugging Face token. May have cold starts/rate limits.",
  };

  const $ = (id) => document.getElementById(id);
  const els = {
    modePill: $("mode-pill"),
    modeText: $("mode-text"),
    enginePillText: $("engine-pill-text"),
    prompt: $("prompt"),
    charCount: $("char-count"),
    enhanceBtn: $("enhance-btn"),
    clearPromptBtn: $("clear-prompt-btn"),
    engineSelect: $("engine-select"),
    engineHint: $("engine-hint"),
    modelSelect: $("model-select"),
    styleSelect: $("style-select"),
    settingsToggle: $("settings-toggle"),
    settingsBody: $("settings-body"),
    settingsChevron: $("settings-chevron"),
    hfToken: $("hf-token"),
    saveSettingsBtn: $("save-settings-btn"),
    clearSettingsBtn: $("clear-settings-btn"),
    generateBtn: $("generate-btn"),
    generateLabel: $("generate-label"),
    progressBlock: $("progress-block"),
    progressFill: $("progress-fill"),
    progressStatus: $("progress-status"),
    progressTimer: $("progress-timer"),
    imageStage: $("image-stage"),
    stageEmpty: $("stage-empty"),
    resultImage: $("result-image"),
    downloadBtn: $("download-btn"),
    copyBtn: $("copy-btn"),
    metaEngine: $("meta-engine"),
    metaModel: $("meta-model"),
    metaSize: $("meta-size"),
    metaSeed: $("meta-seed"),
    metaTime: $("meta-time"),
    galleryStrip: $("gallery-strip"),
    clearGalleryBtn: $("clear-gallery-btn"),
    devBtn: $("dev-btn"),
    devModal: $("dev-modal"),
    modalClose: $("modal-close"),
    printResumeBtn: $("print-resume-btn"),
    galleryToggle: $("gallery-toggle"),
    toastStack: $("toast-stack"),
  };

  let mode = "cloud"; // cloud | fullstack
  let busy = false;
  let history = [];
  let settings = loadSettings();
  let currentRatio = { w: 1024, h: 1024, label: "1:1" };
  let lastImage = null;

  /* --------------------------------- Utils --------------------------------- */
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const clamp = (n, a, b) => Math.max(a, Math.min(b, n));
  const randSeed = () => Math.floor(Math.random() * 1_000_000_000);

  function toast(msg, type = "info") {
    const el = document.createElement("div");
    el.className = `toast ${type}`;
    el.textContent = msg;
    els.toastStack.appendChild(el);
    setTimeout(() => { el.style.opacity = "0"; setTimeout(() => el.remove(), 200); }, 3600);
  }

  function setMode(next) {
    mode = next;
    const dot = els.modePill.querySelector(".status-dot");
    dot.className = "status-dot " + (mode === "fullstack" ? "dot-green" : "dot-cyan");
    els.modeText.textContent = mode === "fullstack" ? "Full-stack: Java + Python" : "Cloud: Browser mode";
  }

  async function detectMode() {
    const isLocal = ["localhost", "127.0.0.1", "::1"].includes(location.hostname);
    if (!isLocal) { setMode("cloud"); return; }
    try {
      const ctrl = new AbortController();
      setTimeout(() => ctrl.abort(), 1200);
      const res = await fetch(`${GATEWAY}/api/health`, { signal: ctrl.signal });
      if (res.ok) { setMode("fullstack"); return; }
    } catch (_) {}
    setMode("cloud");
  }

  /* ------------------------------- Settings -------------------------------- */
  function loadSettings() {
    try { return JSON.parse(localStorage.getItem(STORAGE_SETTINGS) || "{}") || {}; }
    catch { return {}; }
  }
  function saveSettings() {
    settings = { hfToken: (els.hfToken?.value || "").trim() };
    localStorage.setItem(STORAGE_SETTINGS, JSON.stringify(settings));
    toast("Settings saved locally.", "success");
  }
  function applySettingsToUI() {
    if (els.hfToken) els.hfToken.value = settings.hfToken || "";
  }

  /* ------------------------------- Styles ---------------------------------- */
  const FALLBACK_STYLES = [
    { id: "photorealistic", label: "Photorealistic", promptSuffix: "photorealistic, natural lighting, 35mm lens" },
    { id: "cyberpunk", label: "Cyberpunk", promptSuffix: "cyberpunk, neon accents, futuristic city, cinematic" },
    { id: "anime", label: "Anime", promptSuffix: "anime style, vibrant, clean line art" },
    { id: "3d-render", label: "3D Render", promptSuffix: "3D render, octane lighting, detailed materials" },
    { id: "cinematic", label: "Cinematic", promptSuffix: "cinematic still, dramatic lighting, depth of field" },
    { id: "dark-hacker", label: "Dark Hacker", promptSuffix: "dark hacker aesthetic, terminal glow, moody" },
  ];

  function fillStyles(styles) {
    els.styleSelect.innerHTML = `<option value="">No style preset</option>`;
    styles.forEach((s) => {
      const opt = document.createElement("option");
      opt.value = s.id || s.label || "";
      opt.textContent = s.label || s.id;
      opt.dataset.suffix = s.promptSuffix || s.suffix || "";
      els.styleSelect.appendChild(opt);
    });
  }

  async function loadStyles() {
    try {
      const res = await fetch("./data/styles.json", { cache: "no-store" });
      if (!res.ok) throw 0;
      const data = await res.json();
      const styles = Array.isArray(data) ? data : data.styles;
      fillStyles(Array.isArray(styles) && styles.length ? styles : FALLBACK_STYLES);
    } catch { fillStyles(FALLBACK_STYLES); }
  }

  function selectedStyleSuffix() {
    const opt = els.styleSelect.options[els.styleSelect.selectedIndex];
    return (opt && opt.dataset.suffix) || "";
  }

  /* ------------------------------- Engine UI ------------------------------- */
  function syncEngineUI() {
    const engine = els.engineSelect.value;
    const models = ENGINE_MODELS[engine] || ENGINE_MODELS.pollinations;
    const prev = els.modelSelect.value;
    els.modelSelect.innerHTML = "";
    models.forEach((m) => {
      const opt = document.createElement("option");
      opt.value = m.value; opt.textContent = m.label;
      els.modelSelect.appendChild(opt);
    });
    if (models.some((m) => m.value === prev)) els.modelSelect.value = prev;
    els.engineHint.textContent = ENGINE_HINTS[engine] || "";
    els.enginePillText.textContent = els.modelSelect.value;
  }

  /* --------------------------- Prompt enhancement -------------------------- */
  function localEnhance(prompt) {
    let p = prompt.trim();
    if (!p) return p;
    if (!/photorealistic|8k|detailed|cinematic|sharp focus|natural lighting/i.test(p)) {
      p += ", " + REALISM_SUFFIX;
    }
    return p;
  }

  async function handleEnhance() {
    const raw = els.prompt.value.trim();
    if (!raw) { toast("Write a prompt first.", "error"); return; }
    els.prompt.value = localEnhance(raw);
    updateCharCount();
    toast("Prompt enhanced for better realism.", "success");
  }

  /* ----------------------------- Image generation -------------------------- */
  function buildPrompt() {
    const base = els.prompt.value.trim();
    const style = selectedStyleSuffix();
    const parts = [base];
    if (style) parts.push(style);
    if (!/photorealistic|8k|ultra-detailed|sharp focus/i.test(base)) parts.push(REALISM_SUFFIX);
    return parts.join(", ").replace(/\s+/g, " ").trim();
  }

  function setProgress(pct, status) {
    els.progressBlock.hidden = false;
    els.progressFill.style.width = `${clamp(pct, 0, 100)}%`;
    if (status) els.progressStatus.textContent = status;
  }

  async function generatePollinations(prompt, model, w, h, seed) {
    const url = `https://image.pollinations.ai/prompt/${encodeURIComponent(prompt)}?width=${w}&height=${h}&seed=${seed}&model=${encodeURIComponent(model)}&nologo=true&enhance=true`;
    // Preload to ensure image is actually generated
    await loadImage(url);
    return { url, seed, model, engine: "Pollinations" };
  }

  function loadImage(url, timeoutMs = 120000) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      const t = setTimeout(() => { img.src = ""; reject(new Error("Image load timed out")); }, timeoutMs);
      img.onload = () => { clearTimeout(t); resolve(url); };
      img.onerror = () => { clearTimeout(t); reject(new Error("Failed to load generated image")); };
      img.src = url;
    });
  }

  async function generateHuggingFace(prompt, model) {
    const token = (els.hfToken?.value || settings.hfToken || "").trim();
    const headers = { "Content-Type": "application/json" };
    if (token) headers.Authorization = `Bearer ${token}`;
    setProgress(25, "Calling Hugging Face...");
    const res = await fetch(`https://api-inference.huggingface.co/models/${encodeURIComponent(model)}`, {
      method: "POST",
      headers,
      body: JSON.stringify({ inputs: prompt })
    });
    if (!res.ok) {
      const t = await res.text().catch(() => "");
      throw new Error(`Hugging Face error ${res.status}. ${t.includes("loading") ? "Model is loading, try again in a moment." : "Try Pollinations for instant free generation."}`);
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    return { url, seed: randSeed(), model, engine: "Hugging Face", blob };
  }

  async function generateViaBackend(payload) {
    const res = await fetch(`${GATEWAY}/api/generate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok || data.status === "error") throw new Error(data.message || `Backend error ${res.status}`);
    const url = normalizeImageUrl(data.imageUrl);
    await loadImage(url);
    return { url, seed: data.seed || payload.seed, model: data.engine || payload.model, engine: "Full-Stack Gateway" };
  }

  function normalizeImageUrl(url) {
    if (!url) return url;
    if (/^(https?:|blob:|data:)/i.test(url)) return url;
    if (url.startsWith("./")) return `${GATEWAY}/${url.slice(2)}`;
    if (url.startsWith("/")) return `${GATEWAY}${url}`;
    return `${GATEWAY}/${url}`;
  }

  async function handleGenerate() {
    if (busy) return;
    const rawPrompt = els.prompt.value.trim();
    if (!rawPrompt) { toast("Please enter a prompt.", "error"); els.prompt.focus(); return; }

    const engine = els.engineSelect.value;
    const model = els.modelSelect.value;
    const w = currentRatio.w;
    const h = currentRatio.h;
    const seed = randSeed();
    const prompt = buildPrompt();

    busy = true;
    els.generateBtn.disabled = true;
    els.generateLabel.textContent = "Generating...";
    setProgress(8, "Preparing prompt...");
    const start = performance.now();
    const timer = setInterval(() => { els.progressTimer.textContent = `${Math.round(performance.now() - start)} ms`; }, 100);

    try {
      let result;
      if (engine === "huggingface") {
        result = await generateHuggingFace(prompt, model);
      } else if (mode === "fullstack") {
        try {
          setProgress(20, "Routing through Java + Python gateway...");
          result = await generateViaBackend({ prompt, originalPrompt: rawPrompt, style: els.styleSelect.value, width: w, height: h, seed, model, engine: "pollinations" });
        } catch (e) {
          console.warn("Gateway failed, falling back to browser", e);
          setProgress(25, "Using browser free mode...");
          result = await generatePollinations(prompt, model, w, h, seed);
        }
      } else {
        setProgress(20, "Generating with free Pollinations AI...");
        result = await generatePollinations(prompt, model, w, h, seed);
      }

      clearInterval(timer);
      const elapsed = Math.round(performance.now() - start);
      setProgress(100, "Done");
      showResult({ ...result, elapsed, prompt: rawPrompt, w, h });
      addHistory({ prompt: rawPrompt, url: result.url, seed: result.seed, model: result.model, engine: result.engine, w, h, time: Date.now() });
      toast("Image generated successfully.", "success");
    } catch (err) {
      clearInterval(timer);
      console.error(err);
      setProgress(0, "Failed");
      toast(err.message || "Generation failed.", "error");
    } finally {
      busy = false;
      els.generateBtn.disabled = false;
      els.generateLabel.textContent = "Generate Image";
      setTimeout(() => { if (!busy) els.progressBlock.hidden = true; }, 1200);
    }
  }

  /* ------------------------------ Result & history ------------------------- */
  function showResult(r) {
    els.stageEmpty.hidden = true;
    els.resultImage.hidden = false;
    els.resultImage.src = r.url;
    els.downloadBtn.disabled = false;
    els.copyBtn.disabled = false;
    els.metaEngine.textContent = r.engine || "—";
    els.metaModel.textContent = r.model || "—";
    els.metaSize.textContent = `${r.w}×${r.h}`;
    els.metaSeed.textContent = String(r.seed ?? "—");
    els.metaTime.textContent = `${r.elapsed} ms`;
    els.enginePillText.textContent = r.model || els.modelSelect.value;
    lastImage = r;
  }

  function loadHistory() {
    try { history = JSON.parse(localStorage.getItem(STORAGE_HISTORY) || "[]") || []; }
    catch { history = []; }
    renderHistory();
  }
  function addHistory(item) {
    history.unshift(item);
    history = history.slice(0, 24);
    localStorage.setItem(STORAGE_HISTORY, JSON.stringify(history));
    renderHistory();
  }
  function renderHistory() {
    els.galleryStrip.innerHTML = "";
    if (!history.length) {
      els.galleryStrip.innerHTML = `<div class="gallery-empty">No images yet. Your generations will appear here.</div>`;
      return;
    }
    history.forEach((item) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "gallery-item";
      btn.title = item.prompt || "Generated image";
      const img = document.createElement("img");
      img.loading = "lazy";
      img.src = item.url;
      img.alt = item.prompt || "Generated image";
      btn.appendChild(img);
      btn.addEventListener("click", () => {
        showResult({ ...item, elapsed: item.elapsed || 0 });
        window.scrollTo({ top: 0, behavior: "smooth" });
      });
      els.galleryStrip.appendChild(btn);
    });
  }

  /* --------------------------------- Modal --------------------------------- */
  function openModal() { els.devModal.hidden = false; document.body.style.overflow = "hidden"; }
  function closeModal() { els.devModal.hidden = true; document.body.style.overflow = ""; }

  /* ---------------------------------- UI ----------------------------------- */
  function updateCharCount() { els.charCount.textContent = `${els.prompt.value.length} / 1000`; }

  function bindEvents() {
    els.prompt.addEventListener("input", updateCharCount);
    els.clearPromptBtn.addEventListener("click", () => { els.prompt.value = ""; updateCharCount(); els.prompt.focus(); });
    els.enhanceBtn.addEventListener("click", handleEnhance);
    els.engineSelect.addEventListener("change", syncEngineUI);
    els.modelSelect.addEventListener("change", () => { els.enginePillText.textContent = els.modelSelect.value; });

    document.querySelectorAll(".chip[data-ratio]").forEach((chip) => {
      chip.addEventListener("click", () => {
        document.querySelectorAll(".chip[data-ratio]").forEach((c) => c.classList.remove("active"));
        chip.classList.add("active");
        currentRatio = { w: parseInt(chip.dataset.w, 10), h: parseInt(chip.dataset.h, 10), label: chip.dataset.ratio };
      });
    });

    els.settingsToggle.addEventListener("click", () => {
      const expanded = els.settingsToggle.getAttribute("aria-expanded") === "true";
      els.settingsToggle.setAttribute("aria-expanded", String(!expanded));
      els.settingsBody.hidden = expanded;
    });
    els.saveSettingsBtn.addEventListener("click", saveSettings);
    els.clearSettingsBtn.addEventListener("click", () => {
      localStorage.removeItem(STORAGE_SETTINGS);
      settings = {};
      applySettingsToUI();
      toast("Settings cleared.", "info");
    });

    els.generateBtn.addEventListener("click", handleGenerate);
    els.prompt.addEventListener("keydown", (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); handleGenerate(); }
    });

    els.downloadBtn.addEventListener("click", async () => {
      if (!lastImage?.url) return;
      try {
        const res = await fetch(lastImage.url);
        const blob = await res.blob();
        const a = document.createElement("a");
        const u = URL.createObjectURL(blob);
        a.href = u; a.download = `raj-ai-studio-${Date.now()}.png`;
        document.body.appendChild(a); a.click(); a.remove();
        URL.revokeObjectURL(u);
      } catch { window.open(lastImage.url, "_blank"); }
    });
    els.copyBtn.addEventListener("click", async () => {
      if (!lastImage?.url) return;
      try { await navigator.clipboard.writeText(lastImage.url); toast("Image URL copied.", "success"); }
      catch { toast("Copy failed.", "error"); }
    });

    els.clearGalleryBtn.addEventListener("click", () => {
      history = []; localStorage.removeItem(STORAGE_HISTORY); renderHistory();
      toast("Local gallery cleared.", "info");
    });
    els.galleryToggle.addEventListener("click", () => document.querySelector(".gallery-drawer").scrollIntoView({ behavior: "smooth" }));

    els.devBtn.addEventListener("click", openModal);
    els.modalClose.addEventListener("click", closeModal);
    els.devModal.addEventListener("click", (e) => { if (e.target === els.devModal) closeModal(); });
    document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !els.devModal.hidden) closeModal(); });
    els.printResumeBtn.addEventListener("click", () => window.print());
  }

  /* ---------------------------------- Init --------------------------------- */
  async function init() {
    applySettingsToUI();
    syncEngineUI();
    updateCharCount();
    bindEvents();
    loadHistory();
    await detectMode();
    await loadStyles();
  }

  init();
})();
