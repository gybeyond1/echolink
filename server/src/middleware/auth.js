const jwt = require("jsonwebtoken");

function authMiddleware(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return res.status(401).json({ error: "Missing or invalid Authorization header" });
  }

  const token = authHeader.substring(7);
  try {
    const decoded = jwt.verify(token, process.env.JWT_SECRET || "default-secret");
    req.userId = decoded.userId;
    req.username = decoded.username;
    req.role = decoded.role || "user";
    next();
  } catch (err) {
    return res.status(401).json({ error: "Invalid or expired token" });
  }
}

// 仅管理员可访问
function requireAdmin(req, res, next) {
  if (req.role !== "admin") {
    return res.status(403).json({ error: "Admin privileges required" });
  }
  next();
}

module.exports = { authMiddleware, requireAdmin };
