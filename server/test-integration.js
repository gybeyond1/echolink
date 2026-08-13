// 集成测试：验证通知同步 + 本机过滤 + 公共话题
const WebSocket = require("ws");

const BASE = "http://127.0.0.1:3000";
const WS_BASE = "ws://127.0.0.1:3000/ws";

let failures = 0;
function check(name, cond, extra = "") {
  console.log(`${cond ? "PASS" : "FAIL"} | ${name} ${extra}`);
  if (!cond) failures++;
}

async function request(path, method = "GET", body = null, token = null) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json() };
}

function connectWs(token, deviceId) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS_BASE}?token=${token}&device_id=${deviceId}`);
    const inbox = [];
    ws.on("open", () => resolve({ ws, inbox }));
    ws.on("message", (m) => {
      const data = JSON.parse(m.toString());
      inbox.push(data);
    });
    ws.on("error", reject);
    setTimeout(() => reject(new Error("WS connect timeout")), 5000);
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  // 1. 注册 + 登录
  await request("/api/auth/register", "POST", { username: "topicuser", password: "test123456" });
  const login = await request("/api/auth/login", "POST", { username: "topicuser", password: "test123456" });
  const token = login.json.token;
  check("登录成功", !!token);

  // 2. 注册两个设备
  const devA = await request("/api/devices/register", "POST", { device_name: "Phone A", platform: "android" }, token);
  const devB = await request("/api/devices/register", "POST", { device_name: "Phone B", platform: "android" }, token);
  const idA = devA.json.device_id;
  const idB = devB.json.device_id;
  check("注册设备 A/B", idA > 0 && idB > 0, `(A=${idA}, B=${idB})`);

  // 3. 建立两个 WS 连接
  const connA = await connectWs(token, idA);
  const connB = await connectWs(token, idB);
  await sleep(500);
  check("WS A/B 连接成功", connA.ws.readyState === 1 && connB.ws.readyState === 1);

  // 4. B 订阅话题 work
  connB.ws.send(JSON.stringify({ type: "subscribe", topic: "work" }));
  await sleep(500);
  check("B 订阅话题成功", connB.inbox.some((m) => m.type === "subscribed" && m.topic === "work"));

  // 5. A 通过 WebSocket 向话题 work 发消息
  connA.inbox.length = 0;
  connB.inbox.length = 0;
  connA.ws.send(JSON.stringify({ type: "publish", topic: "work", title: "", text: "hello from A", sender_name: "phoneA" }));
  await sleep(800);

  const bGot = connB.inbox.some((m) => m.type === "topic_message" && m.data.text === "hello from A");
  const aGotSelf = connA.inbox.some((m) => m.type === "topic_message");
  check("WS 发布: B 收到", bGot);
  check("WS 发布: A 没收到自己的消息(本机过滤)", !aGotSelf);

  // 6. A 通过 HTTP 向话题 work 发消息（device_id = A）
  const pub = await request("/api/topics/work/publish", "POST", { title: "", text: "http from A", device_id: idA }, token);
  check("HTTP 发布成功", pub.status === 201);
  await sleep(800);
  const bGotHttp = connB.inbox.some((m) => m.type === "topic_message" && m.data.text === "http from A");
  const aGotHttpSelf = connA.inbox.some((m) => m.type === "topic_message" && m.data.text === "http from A");
  check("HTTP 发布: B 收到", bGotHttp);
  check("HTTP 发布: A 没收到自己发的(按 device_id 过滤)", !aGotHttpSelf);

  // 7. 话题历史消息查询
  const hist = await request("/api/topics/work/messages?limit=10", "GET", null, token);
  check("话题历史包含 2 条", hist.json.messages.length === 2, `(count=${hist.json.messages.length})`);

  // 8. 通知本机过滤: A 发通知(device_id=A)，B 收到、A 不收到
  connA.inbox.length = 0;
  connB.inbox.length = 0;
  await request("/api/notifications", "POST", {
    package_name: "com.tencent.mm", app_name: "微信",
    title: "新消息", text: "你好", timestamp: Date.now(), device_id: idA,
  }, token);
  await sleep(800);
  const bGotNotif = connB.inbox.some((m) => m.type === "notification" && m.data.title === "新消息");
  const aGotNotifSelf = connA.inbox.some((m) => m.type === "notification" && m.data.title === "新消息");
  check("通知同步: B 收到", bGotNotif);
  check("通知同步: A 没收到自己的(本机过滤)", !aGotNotifSelf);

  // 9. 话题列表
  const list = await request("/api/topics", "GET", null, token);
  check("话题列表包含 work", list.json.topics.some((t) => t.topic === "work"));

  // 10. 非法话题名校验
  const bad = await request("/api/topics/中文话题/publish", "POST", { text: "x" }, token);
  check("非法话题名被拒绝", bad.status === 400);

  // 11. C 设备订阅后能收到 A/B 发的历史 + 新消息
  const devC = await request("/api/devices/register", "POST", { device_name: "Tablet C", platform: "android" }, token);
  const idC = devC.json.device_id;
  const connC = await connectWs(token, idC);
  connC.ws.send(JSON.stringify({ type: "subscribe", topic: "work" }));
  await sleep(500);
  connA.ws.send(JSON.stringify({ type: "publish", topic: "work", title: "来自A", text: "third message", sender_name: "phoneA" }));
  await sleep(800);
  const cGot = connC.inbox.some((m) => m.type === "topic_message" && m.data.text === "third message");
  check("新设备 C 订阅后实时收到消息", cGot);

  connA.ws.close();
  connB.ws.close();
  connC.ws.close();

  console.log(failures === 0 ? "\n✅ 全部测试通过" : `\n❌ ${failures} 项测试失败`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error("测试异常:", e);
  process.exit(1);
});
