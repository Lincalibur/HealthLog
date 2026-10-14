// Loads summary.json (built by scripts/build_summary.py) and draws the dashboard.
(function () {
  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const $ = (id) => document.getElementById(id);

  // Repo links: on GitHub Pages the URL is <owner>.github.io/<repo>/.
  (function repoLinks() {
    const m = location.hostname.match(/^([^.]+)\.github\.io$/);
    const repo = location.pathname.split("/").filter(Boolean)[0];
    const base = m && repo ? `https://github.com/${m[1]}/${repo}` : null;
    document.querySelectorAll(".repo-link").forEach((a) => {
      if (base) a.href = a.dataset.path ? `${base}/${a.dataset.path}` : base;
      else a.removeAttribute("href");
    });
  })();

  function ago(iso) {
    const h = (Date.now() - Date.parse(iso)) / 36e5;
    if (h < 1) return `${Math.max(1, Math.round(h * 60))} min ago`;
    if (h < 48) return `${Math.round(h)} h ago`;
    return `${Math.round(h / 24)} days ago`;
  }

  function tiles(s) {
    $("fw").textContent = s.firmware || "3.24";
    if (s.lastData) {
      $("t-sync").textContent = ago(s.lastData);
      $("t-sync-s").textContent = new Date(s.lastData).toISOString().replace("T", " ").slice(0, 16) + " UTC";
      const hours = (Date.now() - Date.parse(s.lastData)) / 36e5;
      $("t-sync").parentElement.classList.add(hours < 7 ? "ok" : "warn");
    }
    $("t-days").textContent = s.daysLogged.toLocaleString();
    $("t-readings").textContent = s.readingsLogged.toLocaleString();
    if (s.battery) {
      $("t-battery").textContent = `${s.battery.v}%`;
      $("t-battery-s").textContent = ago(s.battery.t);
    }
  }

  function features(s) {
    const body = $("features");
    for (const f of s.features) {
      const tr = document.createElement("tr");
      const chip = f.status.replace(" ", "-");
      const rate = f.rate30 == null
        ? `<span class="muted">no attempts yet</span>`
        : `<div class="rate"><span class="bar"><i style="width:${Math.round(f.rate30 * 100)}%"></i></span>` +
          `${Math.round(f.rate30 * 100)}% <span class="muted">(${f.ok30}/${f.attempts30})</span></div>`;
      tr.innerHTML = `<td>${f.name}</td><td><span class="chip ${chip}">${f.status}</span></td><td>${rate}</td>`;
      body.appendChild(tr);
    }
  }

  function charts(s) {
    if (!window.Chart) return;
    const text = css("--muted"), grid = css("--line");
    Chart.defaults.color = text;
    Chart.defaults.borderColor = grid;
    Chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
    Chart.defaults.maintainAspectRatio = false;
    Chart.defaults.plugins.legend.labels.boxWidth = 12;

    const days = s.days.map((d) => d.date);
    new Chart($("c-steps"), {
      type: "bar",
      data: { labels: days, datasets: [{ label: "Steps", data: s.days.map((d) => d.steps), backgroundColor: css("--c1"), borderRadius: 3 }] },
      options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
    });

    const hrDays = s.days.filter((d) => d.hr);
    new Chart($("c-hr"), {
      data: {
        labels: hrDays.map((d) => d.date),
        datasets: [
          { type: "bar", label: "Low–high", data: hrDays.map((d) => [d.hr.min, d.hr.max]), backgroundColor: css("--c2") + "66", borderColor: css("--c2"), borderWidth: 1, borderSkipped: false, borderRadius: 3 },
          { type: "line", label: "Average", data: hrDays.map((d) => d.hr.avg), borderColor: css("--c2"), backgroundColor: css("--c2"), showLine: false, pointRadius: 4 },
        ],
      },
      options: { scales: { y: { suggestedMin: 40, suggestedMax: 140, title: { display: true, text: "bpm" } } } },
    });

    const xy = (rows, key) => rows.map((r) => ({ x: Date.parse(r.t), y: r[key] }));
    new Chart($("c-spot"), {
      type: "scatter",
      data: {
        datasets: [
          { label: "SpO2 %", data: xy(s.spo2, "v"), backgroundColor: css("--c1"), yAxisID: "y" },
          { label: "BP value 1", data: xy(s.bp, "v"), backgroundColor: css("--c2"), yAxisID: "y2" },
          { label: "BP value 2", data: xy(s.bp, "v2"), backgroundColor: css("--c4"), yAxisID: "y2" },
        ],
      },
      options: {
        scales: {
          x: { type: "linear", ticks: { callback: (v) => new Date(v).toISOString().slice(5, 10) } },
          y: { position: "left", suggestedMin: 85, suggestedMax: 100, title: { display: true, text: "SpO2 %" } },
          y2: { position: "right", suggestedMin: 50, suggestedMax: 150, grid: { drawOnChartArea: false }, title: { display: true, text: "mmHg (order unverified)" } },
        },
      },
    });

    new Chart($("c-battery"), {
      type: "line",
      data: { datasets: [{ label: "Battery %", data: xy(s.batteryHistory, "v"), borderColor: css("--c3"), backgroundColor: css("--c3"), pointRadius: 2, tension: 0.2 }] },
      options: {
        plugins: { legend: { display: false } },
        scales: { x: { type: "linear", ticks: { callback: (v) => new Date(v).toISOString().slice(5, 10) } }, y: { min: 0, max: 100 } },
      },
    });

    const outcomes = [["ok", "--c3"], ["timeout", "--c2"], ["out_of_range", "--c4"], ["no_reply", "--c5"], ["error", "--bad"]];
    new Chart($("c-rel"), {
      type: "bar",
      data: {
        labels: s.reliability.map((r) => r.date),
        datasets: outcomes.map(([k, c]) => ({ label: k.replace(/_/g, " "), data: s.reliability.map((r) => r[k]), backgroundColor: css(c) })),
      },
      options: { scales: { x: { stacked: true }, y: { stacked: true, beginAtZero: true } } },
    });
  }

  fetch("summary.json", { cache: "no-store" })
    .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
    .then((s) => {
      tiles(s);
      features(s);
      if (!s.readingsLogged) $("empty").hidden = false;
      charts(s);
    })
    .catch(() => { $("empty").hidden = false; });
})();
