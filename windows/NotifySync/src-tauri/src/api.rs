// EchoLink Desktop —— REST + WebSocket 客户端（对齐 Android 端逻辑）
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

pub type Result<T> = std::result::Result<T, String>;

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Topic {
    pub id: i64,
    pub name: String,
    pub title: Option<String>,
    pub kind: String,
    pub display_name: String,
    pub avatar: Option<String>,
    pub message_count: i64,
    pub last_message: Option<String>,
    pub last_message_at: Option<u64>,
    pub unread_count: i64,
    pub my_role: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Message {
    pub id: i64,
    pub topic: String,
    pub user_id: i64,
    pub sender_name: String,
    pub sender_display_name: Option<String>,
    pub sender_avatar: Option<String>,
    pub text: Option<String>,
    pub title: Option<String>,
    pub media_type: Option<String>,
    pub media_url: Option<String>,
    pub media_name: Option<String>,
    pub media_size: Option<i64>,
    pub device_name: Option<String>,
    pub timestamp: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Friend {
    pub username: String,
    pub display_name: Option<String>,
    pub avatar: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DeviceInfo {
    pub id: i64,
    pub device_name: String,
    pub platform: Option<String>,
    pub last_seen: Option<u64>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct InstalledApp {
    pub name: String,
    pub exe: String,
}

#[derive(Clone, Debug)]
pub struct Client {
    pub server: String,
    pub token: String,
    pub username: String,
    pub user_id: i64,
    pub role: String,
    pub display_name: String,
    pub avatar: String,
    // 本地缓存（避免每次渲染都重新拉列表）
    pub topics_cache: Vec<Topic>,
    pub filters_cache: Vec<FilterEntry>,
}

impl Default for Client {
    fn default() -> Self {
        Client {
            server: String::new(),
            token: String::new(),
            username: String::new(),
            user_id: 0,
            role: "user".to_string(),
            display_name: String::new(),
            avatar: String::new(),
            topics_cache: Vec::new(),
            filters_cache: Vec::new(),
        }
    }
}

impl Client {
    pub fn new(server: String) -> Self {
        Client {
            server: server.trim_end_matches('/').to_string(),
            token: String::new(),
            username: String::new(),
            user_id: 0,
            role: "user".to_string(),
            display_name: String::new(),
            avatar: String::new(),
            topics_cache: Vec::new(),
            filters_cache: Vec::new(),
        }
    }

    /// 返回缓存中话题名列表（供 WS 订阅使用）
    pub fn topics_cache_names(&self) -> Vec<Topic> {
        self.topics_cache.clone()
    }

    pub fn authed(&self) -> bool {
        !self.token.is_empty()
    }

    fn auth_header(&self) -> HeaderMap {
        let mut h = HeaderMap::new();
        if !self.token.is_empty() {
            if let Ok(v) = HeaderValue::from_str(&format!("Bearer {}", self.token)) {
                h.insert(AUTHORIZATION, v);
            }
        }
        h
    }

    // ---- Auth ----
    pub async fn login(&mut self, username: &str, password: &str) -> Result<()> {
        let body = serde_json::json!({ "username": username, "password": password });
        let r: AuthResp = self.post("/api/auth/login", &body).await?;
        self.apply_auth(r.token, r.user);
        Ok(())
    }

    pub async fn register(&mut self, username: &str, password: &str) -> Result<()> {
        let body = serde_json::json!({ "username": username, "password": password });
        let r: AuthResp = self.post("/api/auth/register", &body).await?;
        self.apply_auth(r.token, r.user);
        Ok(())
    }

    pub async fn me(&mut self) -> Result<()> {
        let r: MeResp = self.get("/api/auth/me").await?;
        self.username = r.user.username.clone();
        self.role = r.user.role.clone().unwrap_or_else(|| "user".to_string());
        self.user_id = r.user.id.unwrap_or(self.user_id);
        self.display_name = r.user.display_name.clone().unwrap_or_default();
        self.avatar = r.user.avatar.clone().unwrap_or_default();
        Ok(())
    }

    fn apply_auth(&mut self, token: String, user: AuthUser) {
        self.token = token;
        self.username = user.username.clone();
        self.user_id = user.id.unwrap_or(0);
        self.role = user.role.clone().unwrap_or_else(|| "user".to_string());
        self.display_name = user.display_name.clone().unwrap_or_default();
        self.avatar = user.avatar.clone().unwrap_or_default();
    }

    // ---- Topics ----
    pub async fn topics(&mut self) -> Result<Vec<Topic>> {
        let r: TopicsResp = self.get("/api/topics").await?;
        self.topics_cache = r.topics.clone();
        Ok(r.topics)
    }

    pub async fn messages(&self, topic: &str, limit: u32) -> Result<Vec<Message>> {
        let r: MessagesResp = self
            .get(&format!(
                "/api/topics/{}/messages?limit={}",
                urlencode(topic),
                limit
            ))
            .await?;
        Ok(r.messages)
    }

    pub async fn publish(&self, topic: &str, text: &str) -> Result<Message> {
        let body = serde_json::json!({ "text": text, "sender_name": self.username });
        let r: PublishResp = self
            .post(&format!("/api/topics/{}/publish", urlencode(topic)), &body)
            .await?;
        Ok(r.topic_message)
    }

    pub async fn delete_message(&self, topic: &str, id: i64) -> Result<()> {
        self.delete(&format!(
            "/api/topics/{}/messages/{}",
            urlencode(topic),
            id
        ))
        .await
    }

    // ---- Friends ----
    pub async fn friends(&self) -> Result<Vec<Friend>> {
        let r: FriendsResp = self.get("/api/friends").await?;
        Ok(r.friends)
    }

    pub async fn add_friend_req(&self, username: &str) -> Result<()> {
        let body = serde_json::json!({ "username": username, "message": "" });
        self.post("/api/friends/requests", &body).await?;
        Ok(())
    }

    pub async fn friend_chat(&self, username: &str) -> Result<String> {
        let r: serde_json::Value = self
            .post(&format!("/api/friends/chat/{}", urlencode(username)), &serde_json::json!({}))
            .await?;
        Ok(r["topic"].as_str().unwrap_or("").to_string())
    }

    // ---- Devices ----
    pub async fn devices(&self) -> Result<Vec<DeviceInfo>> {
        let r: DevicesResp = self.get("/api/devices").await?;
        Ok(r.devices)
    }

    // ---- Filters (server-side allow-list of synced apps) ----
    pub async fn filters(&self) -> Result<Vec<FilterEntry>> {
        let r: FiltersResp = self.get("/api/filters").await?;
        Ok(r.filters)
    }

    pub async fn add_filter(&self, package_name: &str, app_name: &str) -> Result<()> {
        let body = serde_json::json!({
            "package_name": package_name,
            "app_name": app_name,
            "enabled": true
        });
        self.post("/api/filters", &body).await?;
        Ok(())
    }

    pub async fn del_filter(&self, package_name: &str) -> Result<()> {
        self.delete(&format!("/api/filters/{}", urlencode(package_name)))
            .await
    }

    // ---- Profile ----
    pub async fn set_nickname(&mut self, name: &str) -> Result<()> {
        let body = serde_json::json!({ "display_name": name });
        self.put("/api/user/nickname", &body).await?;
        self.display_name = name.to_string();
        Ok(())
    }

    pub async fn change_password(&self, old: &str, new: &str) -> Result<()> {
        let body = serde_json::json!({ "oldPassword": old, "newPassword": new });
        self.put("/api/auth/change-password", &body).await?;
        Ok(())
    }

    // ---- Notifications (sync records from other devices) ----
    pub async fn notifications(&self, limit: u32) -> Result<Vec<NotifRecord>> {
        let r: NotifsResp = self
            .get(&format!("/api/notifications?limit={}", limit))
            .await?;
        Ok(r.notifications)
    }

    pub async fn report_notification(
        &self,
        package_name: &str,
        app_name: &str,
        title: &str,
        text: &str,
    ) -> Result<()> {
        let body = serde_json::json!({
            "package_name": package_name,
            "app_name": app_name,
            "title": title,
            "text": text,
            "timestamp": now_ms()
        });
        self.post("/api/notifications", &body).await?;
        Ok(())
    }
}

// ---- low-level http ----
use reqwest::header::{HeaderMap, HeaderValue, AUTHORIZATION};
impl Client {
    async fn request<T: for<'de> Deserialize<'de>>(
        &self,
        method: reqwest::Method,
        path: &str,
        body: Option<serde_json::Value>,
    ) -> Result<T> {
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(15))
            .build()
            .map_err(|e| e.to_string())?;
        let mut req = client
            .request(method, format!("{}{}", self.server, path))
            .headers(self.auth_header());
        if let Some(b) = body {
            req = req.json(&b);
        }
        let resp = req.send().await.map_err(|e| e.to_string())?;
        let status = resp.status();
        let text = resp.text().await.unwrap_or_default();
        if !status.is_success() {
            let msg = serde_json::from_str::<serde_json::Value>(&text)
                .ok()
                .and_then(|v| v["error"].as_str().map(|s| s.to_string()))
                .or_else(|| serde_json::from_str::<serde_json::Value>(&text).ok().and_then(|v| v["message"].as_str().map(|s| s.to_string())))
                .unwrap_or_else(|| format!("HTTP {}", status));
            return Err(msg);
        }
        serde_json::from_str::<T>(&text).map_err(|e| format!("解析失败: {}", e))
    }

    async fn get<T: for<'de> Deserialize<'de>>(&self, path: &str) -> Result<T> {
        self.request(reqwest::Method::GET, path, None).await
    }
    async fn post<T: for<'de> Deserialize<'de>>(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> Result<T> {
        self.request(reqwest::Method::POST, path, Some(body.clone())).await
    }
    async fn put<T: for<'de> Deserialize<'de>>(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> Result<T> {
        self.request(reqwest::Method::PUT, path, Some(body.clone())).await
    }
    async fn delete(&self, path: &str) -> Result<()> {
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(15))
            .build()
            .map_err(|e| e.to_string())?;
        let resp = client
            .request(reqwest::Method::DELETE, format!("{}{}", self.server, path))
            .headers(self.auth_header())
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if resp.status().is_success() {
            Ok(())
        } else {
            let status = resp.status();
            let text = resp.text().await.unwrap_or_default();
            let msg = serde_json::from_str::<serde_json::Value>(&text)
                .ok()
                .and_then(|v| v["error"].as_str().map(|s| s.to_string()))
                .unwrap_or_else(|| format!("HTTP {}", status));
            Err(msg)
        }
    }
}

fn urlencode(s: &str) -> String {
    use std::fmt::Write;
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'_' | b'-' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => {
                let _ = write!(out, "%{:02X}", b);
            }
        }
    }
    out
}

// ---- DTOs ----
#[derive(Deserialize)]
struct AuthResp {
    token: String,
    user: AuthUser,
}
#[derive(Deserialize, Clone)]
struct AuthUser {
    username: String,
    id: Option<i64>,
    role: Option<String>,
    display_name: Option<String>,
    avatar: Option<String>,
}
#[derive(Deserialize)]
struct MeResp {
    user: MeUser,
}
#[derive(Deserialize)]
struct MeUser {
    username: String,
    id: Option<i64>,
    role: Option<String>,
    display_name: Option<String>,
    avatar: Option<String>,
}
#[derive(Deserialize)]
struct TopicsResp {
    topics: Vec<Topic>,
}
#[derive(Deserialize)]
struct MessagesResp {
    messages: Vec<Message>,
}
#[derive(Deserialize)]
struct PublishResp {
    topic_message: Message,
}
#[derive(Deserialize)]
struct FriendsResp {
    friends: Vec<Friend>,
}
#[derive(Deserialize, Clone)]
pub struct FilterEntry {
    pub package_name: String,
    pub app_name: String,
    pub enabled: bool,
}
#[derive(Deserialize)]
struct FiltersResp {
    filters: Vec<FilterEntry>,
}
#[derive(Deserialize)]
struct DevicesResp {
    devices: Vec<DeviceInfo>,
}
#[derive(Deserialize)]
struct NotifsResp {
    notifications: Vec<NotifRecord>,
}
#[derive(Deserialize, Clone)]
pub struct NotifRecord {
    pub id: i64,
    pub app_name: Option<String>,
    pub title: Option<String>,
    pub text: Option<String>,
    pub device_name: Option<String>,
    pub timestamp: Option<u64>,
}

pub use std::sync::Arc as _Arc;
