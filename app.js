const authView = document.querySelector("#auth-view");
const dashboardView = document.querySelector("#dashboard-view");
const toast = document.querySelector("#toast");
const loginWrap = document.querySelector("#login-form-wrap");
const registerWrap = document.querySelector("#register-form-wrap");
const passwordForm = document.querySelector("#password-form");
const otpForm = document.querySelector("#otp-form");
const otpCodeWrap = document.querySelector("#otp-code-wrap");
const otpSubmit = document.querySelector("#otp-submit");
const otpStatus = document.querySelector("#otp-status");
const listingList = document.querySelector("#listing-list");
let activeFilter = "All";
let listings = [];
let currentUser = null;
let showMyPosts = false;
let otpRequested = false;
let toastTimer;

function showToast(message, isError = false) {
  toast.textContent = message;
  toast.classList.toggle("error", isError);
  toast.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.add("hidden"), 4600);
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    credentials: "same-origin",
    ...options,
    headers: {
      ...(options.body ? { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" } : {}),
      ...options.headers
    }
  });
  let data;
  try {
    data = await response.json();
  } catch {
    throw new Error("The server returned an unexpected response.");
  }
  if (!response.ok) throw new Error(data.message || "Something went wrong.");
  return data;
}

function formBody(form) {
  return new URLSearchParams(new FormData(form)).toString();
}

function showLogin() {
  registerWrap.classList.add("hidden");
  loginWrap.classList.remove("hidden");
}

function showRegister() {
  loginWrap.classList.add("hidden");
  registerWrap.classList.remove("hidden");
}

document.querySelector("#show-register").addEventListener("click", showRegister);
document.querySelector("#show-register-inline").addEventListener("click", showRegister);
document.querySelector("#show-login").addEventListener("click", showLogin);

document.querySelectorAll(".auth-tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".auth-tab").forEach((other) => {
      const selected = other === tab;
      other.classList.toggle("active", selected);
      other.setAttribute("aria-selected", String(selected));
    });
    const useOtp = tab.dataset.tab === "otp";
    passwordForm.classList.toggle("hidden", useOtp);
    otpForm.classList.toggle("hidden", !useOtp);
    otpRequested = false;
    otpCodeWrap.classList.add("hidden");
    otpForm.elements.code.required = false;
    otpStatus.classList.add("hidden");
    otpStatus.classList.remove("error");
    otpStatus.textContent = "";
    otpSubmit.innerHTML = 'Send me a code <span>→</span>';
  });
});

document.querySelector(".show-password").addEventListener("click", (event) => {
  const input = passwordForm.elements.password;
  const visible = input.type === "password";
  input.type = visible ? "text" : "password";
  event.currentTarget.textContent = visible ? "Hide" : "Show";
  event.currentTarget.setAttribute("aria-label", visible ? "Hide password" : "Show password");
});

passwordForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const submit = passwordForm.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    const data = await api("/api/login/password", { method: "POST", body: formBody(passwordForm) });
    await enterDashboard(data.user);
  } catch (error) {
    showToast(error.message, true);
  } finally {
    submit.disabled = false;
  }
});

otpForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const submit = otpForm.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    if (!otpRequested) {
      const phone = new URLSearchParams({ phone: otpForm.elements.phone.value });
      const data = await api("/api/login/otp/request", { method: "POST", body: phone.toString() });
      otpRequested = true;
      otpCodeWrap.classList.remove("hidden");
      otpForm.elements.code.required = true;
      otpSubmit.innerHTML = 'Verify & sign in <span>→</span>';
      otpStatus.classList.remove("hidden", "error");
      otpStatus.textContent = data.demoCode
        ? `SMS is not configured in this local demo. Your sign-in code is ${data.demoCode}. It expires in 5 minutes.`
        : data.message;
      otpForm.elements.code.focus();
    } else {
      const data = await api("/api/login/otp/verify", { method: "POST", body: formBody(otpForm) });
      await enterDashboard(data.user);
    }
  } catch (error) {
    otpStatus.textContent = error.message;
    otpStatus.classList.remove("hidden");
    otpStatus.classList.add("error");
    showToast(error.message, true);
  } finally {
    submit.disabled = false;
  }
});

document.querySelector("#register-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const submit = form.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    const data = await api("/api/register", { method: "POST", body: formBody(form) });
    await enterDashboard(data.user);
  } catch (error) {
    showToast(error.message, true);
  } finally {
    submit.disabled = false;
  }
});

async function enterDashboard(user) {
  currentUser = user;
  authView.classList.add("hidden");
  dashboardView.classList.remove("hidden");
  document.querySelector("#profile-name").textContent = user.name;
  document.querySelector("#profile-contact").textContent = user.phone || user.email;
  document.querySelector("#profile-avatar").textContent = user.name.trim().charAt(0).toUpperCase();
  document.querySelector("#welcome-name").textContent = user.name.trim().split(/\s+/)[0];
  setTodayLabel();
  try {
    const data = await api("/api/items");
    listings = data.items;
    renderListings();
  } catch (error) {
    showToast(error.message, true);
  }
}

function setTodayLabel() {
  document.querySelector("#today-label").textContent = new Intl.DateTimeFormat("en", {
    weekday: "long", month: "long", day: "numeric"
  }).format(new Date()).toUpperCase();
}

const itemSymbols = { Keys: "⌁", Electronics: "◉", Books: "▤", Accessories: "◈", Clothing: "◇", Other: "✳" };

function formatDate(value) {
  if (!value) return "";
  const date = new Date(`${value}T12:00:00`);
  return new Intl.DateTimeFormat("en", { month: "short", day: "numeric" }).format(date);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  })[character]);
}

function renderListings() {
  const query = document.querySelector("#search-items").value.trim().toLowerCase();
  const visible = listings.filter((item) => {
    const matchesType = activeFilter === "All" || item.type === activeFilter;
    const matchesOwner = !showMyPosts || item.email === currentUser?.email;
    const searchable = `${item.title} ${item.category} ${item.location} ${item.description}`.toLowerCase();
    return matchesType && matchesOwner && searchable.includes(query);
  });
  const myPostCount = listings.filter((item) => item.email === currentUser?.email).length;
  document.querySelector("#all-count").textContent = String(showMyPosts ? myPostCount : listings.length).padStart(2, "0");
  document.querySelector("#browse-count").textContent = String(listings.length);
  document.querySelector("#listings-title").textContent = showMyPosts ? "My posts" : "Recent activity";
  document.querySelector("#live-pill").classList.toggle("hidden", showMyPosts);
  if (!visible.length) {
    listingList.innerHTML = showMyPosts && !myPostCount
      ? '<div class="empty-list">You haven’t posted any items yet.</div>'
      : '<div class="empty-list">No items match that search yet. Try another word or filter.</div>';
    return;
  }
  listingList.innerHTML = visible.map((item) => `
    <article class="listing-card">
      <div class="listing-visual" aria-hidden="true">${itemSymbols[item.category] || "✳"}</div>
      <div class="listing-info">
        <div class="listing-title-row"><h3>${escapeHtml(item.title)}</h3><span class="status-tag ${item.type === "Found" ? "found" : ""}">${escapeHtml(item.type)}</span></div>
        <div class="listing-meta"><span>⌖ ${escapeHtml(item.location)}</span><span>· ${escapeHtml(item.category)}</span><span>by ${escapeHtml(item.owner)}</span></div>
        <p class="listing-description">${escapeHtml(item.description)}</p>
      </div>
      <time class="listing-date">${escapeHtml(formatDate(item.date))}</time>
    </article>`).join("");
}

document.querySelectorAll(".filter-chip").forEach((chip) => {
  chip.addEventListener("click", () => {
    activeFilter = chip.dataset.filter;
    document.querySelectorAll(".filter-chip").forEach((other) => other.classList.toggle("active", other === chip));
    renderListings();
  });
});
document.querySelector("#search-items").addEventListener("input", renderListings);
function selectView(view) {
  showMyPosts = view === "my-posts";
  activeFilter = "All";
  document.querySelector("#search-items").value = "";
  document.querySelectorAll(".filter-chip").forEach((chip) => chip.classList.toggle("active", chip.dataset.filter === "All"));
  document.querySelectorAll(".nav-link[data-view]").forEach((link) => {
    link.classList.toggle("selected", link.dataset.view === view);
  });
  renderListings();
  if (view === "overview") {
    window.scrollTo({ top: 0, behavior: "smooth" });
  } else {
    document.querySelector(".listings-section").scrollIntoView({ behavior: "smooth", block: "start" });
  }
}

document.querySelectorAll(".nav-link[data-view]").forEach((link) => {
  link.addEventListener("click", (event) => {
    event.preventDefault();
    selectView(link.dataset.view);
  });
});
document.querySelector("#browse-all").addEventListener("click", () => {
  selectView("browse");
  document.querySelector("#search-items").focus();
});

const postModal = document.querySelector("#post-modal");
function openPostModal() {
  postModal.classList.remove("hidden");
  postModal.setAttribute("aria-hidden", "false");
  document.body.classList.add("modal-open");
  document.querySelector('#item-form input[name="date"]').value = new Date().toLocaleDateString("en-CA");
  document.querySelector('#item-form input[name="title"]').focus();
}
function closePostModal() {
  postModal.classList.add("hidden");
  postModal.setAttribute("aria-hidden", "true");
  document.body.classList.remove("modal-open");
}
document.querySelector("#open-post").addEventListener("click", openPostModal);
document.querySelector("#open-post-card").addEventListener("click", openPostModal);
document.querySelector("#close-post").addEventListener("click", closePostModal);
postModal.addEventListener("click", (event) => {
  if (event.target === postModal) closePostModal();
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !postModal.classList.contains("hidden")) closePostModal();
});

document.querySelector("#item-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const submit = form.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    await api("/api/items", { method: "POST", body: formBody(form) });
    const data = await api("/api/items");
    listings = data.items;
    selectView("browse");
    form.reset();
    closePostModal();
    showToast("Your item is on the community board. Thanks for being a good neighbor!");
  } catch (error) {
    showToast(error.message, true);
  } finally {
    submit.disabled = false;
  }
});

document.querySelector("#profile-button").addEventListener("click", async () => {
  try {
    await api("/api/logout", { method: "POST", body: "" });
    currentUser = null;
    dashboardView.classList.add("hidden");
    authView.classList.remove("hidden");
    showLogin();
    showToast("You’ve signed out. See you soon!");
  } catch (error) {
    showToast(error.message, true);
  }
});

async function restoreSession() {
  try {
    const data = await api("/api/me");
    if (data.user) {
      await enterDashboard(data.user);
    } else {
      authView.classList.remove("hidden");
    }
  } catch {
    authView.classList.remove("hidden");
  }
}

restoreSession();
