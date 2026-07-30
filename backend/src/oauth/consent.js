(() => {
  const authorizationId = new URLSearchParams(location.search).get(
    "authorization_id",
  );
  const byId = (id) => document.getElementById(id);
  const status = byId("status");
  const errorText = byId("error");
  let client;

  function showError(error) {
    errorText.textContent = error?.message || "요청을 처리하지 못했습니다.";
  }

  async function loadDetails() {
    errorText.textContent = "";
    const { data, error } =
      await client.auth.oauth.getAuthorizationDetails(authorizationId);
    if (error) throw error;
    if (data.redirect_url) {
      location.assign(data.redirect_url);
      return;
    }
    byId("client-name").textContent = data.client.name;
    byId("client-name-copy").textContent = data.client.name;
    byId("user-email").textContent = data.user.email;
    byId("scopes").textContent = data.scope;
    byId("redirect-uri").textContent = data.redirect_uri;
    byId("login").hidden = true;
    byId("consent").hidden = false;
    status.textContent = "아래 내용을 확인하고 연결 여부를 선택하세요.";
  }

  async function start() {
    if (!authorizationId) throw new Error("authorization_id가 없습니다.");
    const configResponse = await fetch("/oauth/config", {
      headers: { Accept: "application/json" },
    });
    if (!configResponse.ok) throw new Error("인증 설정을 불러오지 못했습니다.");
    const config = await configResponse.json();
    client = window.supabase.createClient(
      config.supabaseUrl,
      config.publishableKey,
    );
    const { data } = await client.auth.getSession();
    if (data.session) {
      await loadDetails();
    } else {
      status.textContent = "연결을 승인할 계정으로 먼저 로그인하세요.";
      byId("login").hidden = false;
    }
  }

  byId("send-code").addEventListener("click", async () => {
    try {
      errorText.textContent = "";
      const email = byId("email").value.trim();
      const { error } = await client.auth.signInWithOtp({
        email,
        options: { shouldCreateUser: true },
      });
      if (error) throw error;
      byId("verify").hidden = false;
      status.textContent = "이메일로 전송된 인증 코드를 입력하세요.";
    } catch (error) {
      showError(error);
    }
  });

  byId("verify-code").addEventListener("click", async () => {
    try {
      errorText.textContent = "";
      const { error } = await client.auth.verifyOtp({
        email: byId("email").value.trim(),
        token: byId("code").value.trim(),
        type: "email",
      });
      if (error) throw error;
      await loadDetails();
    } catch (error) {
      showError(error);
    }
  });

  byId("approve").addEventListener("click", async () => {
    try {
      const { data, error } = await client.auth.oauth.approveAuthorization(
        authorizationId,
        { skipBrowserRedirect: true },
      );
      if (error) throw error;
      location.assign(data.redirect_url);
    } catch (error) {
      showError(error);
    }
  });

  byId("deny").addEventListener("click", async () => {
    try {
      const { data, error } = await client.auth.oauth.denyAuthorization(
        authorizationId,
        { skipBrowserRedirect: true },
      );
      if (error) throw error;
      location.assign(data.redirect_url);
    } catch (error) {
      showError(error);
    }
  });

  start().catch(showError);
})();
