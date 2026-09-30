(() => {
  const chatLog = document.getElementById("chatLog");
  const suggestionsEl = document.getElementById("suggestions");
  const form = document.getElementById("chatForm");
  const input = document.getElementById("messageInput");
  const resetBtn = document.getElementById("resetBtn");
  const hero = document.getElementById("hero");
  const traceToggle = document.getElementById("traceToggle");
  const toolTrace = document.getElementById("toolTrace");
  const sendBtn = form.querySelector(".send-btn");

  let sessionId = localStorage.getItem("servicelane_session") || null;
  let showTrace = false;
  let started = false;

  function renderMarkdownish(text) {
    const escaped = text
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
    return escaped
      .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
      .replace(/\*(.+?)\*/g, "<em>$1</em>");
  }

  function addBubble(role, text) {
    const div = document.createElement("div");
    div.className = `bubble ${role}`;
    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = role === "user" ? "You" : "ServiceLane";
    div.appendChild(meta);
    const body = document.createElement("div");
    body.innerHTML = renderMarkdownish(text);
    div.appendChild(body);
    chatLog.appendChild(div);
    chatLog.scrollTop = chatLog.scrollHeight;
  }

  function setSuggestions(items) {
    suggestionsEl.innerHTML = "";
    (items || []).forEach((label) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "chip";
      btn.textContent = label;
      btn.addEventListener("click", () => sendMessage(label));
      suggestionsEl.appendChild(btn);
    });
  }

  function collapseHero() {
    if (!started) {
      hero.classList.add("collapsed");
      started = true;
    }
  }

  async function sendMessage(text) {
    const message = (text || "").trim();
    if (!message) return;
    collapseHero();
    addBubble("user", message);
    setSuggestions([]);
    input.value = "";
    sendBtn.disabled = true;

    try {
      const res = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message, session_id: sessionId }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      sessionId = data.session_id;
      localStorage.setItem("servicelane_session", sessionId);
      addBubble("assistant", data.message);
      setSuggestions(data.suggestions);
      toolTrace.textContent = JSON.stringify(data.tool_trace || [], null, 2);
    } catch (err) {
      addBubble("assistant", `Something went wrong: ${err.message}`);
    } finally {
      sendBtn.disabled = false;
      input.focus();
    }
  }

  form.addEventListener("submit", (e) => {
    e.preventDefault();
    sendMessage(input.value);
  });

  document.querySelectorAll("[data-prompt]").forEach((btn) => {
    btn.addEventListener("click", () => sendMessage(btn.dataset.prompt));
  });

  resetBtn.addEventListener("click", async () => {
    if (sessionId) {
      await fetch(`/api/session/reset?session_id=${encodeURIComponent(sessionId)}`, {
        method: "POST",
      });
    }
    localStorage.removeItem("servicelane_session");
    sessionId = null;
    chatLog.innerHTML = "";
    setSuggestions([]);
    toolTrace.textContent = "";
    hero.classList.remove("collapsed");
    started = false;
    input.focus();
  });

  traceToggle.addEventListener("click", () => {
    showTrace = !showTrace;
    toolTrace.classList.toggle("hidden", !showTrace);
    traceToggle.textContent = showTrace ? "Hide tool trace" : "Show tool trace";
  });

  setSuggestions([
    "I am Ananya Sharma",
    "Book a service",
    "What can you do?",
    "Check status for appt_demo_rahul",
  ]);
})();
