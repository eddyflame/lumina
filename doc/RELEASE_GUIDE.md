# Lumina Reader 发布流程指南

本文档记录了 Lumina Reader 的打包签名规范、本地发布流程与 GitHub Actions 自动化 CI/CD 配置。

---

## 1. 签名与密钥管理规范

> **安全注意**：Android 签名密钥库（`.jks`）以及包含密码的 `keystore.properties` 已被 `.gitignore` 排除，**严禁提交至公共 Git 仓库**。请将密钥文件与密码单独备份至个人密码管理器（如 Bitwarden、1Password 或离线加密介质）。

### 密钥库规范参数
- **存储文件**：`lumina-release.jks`（或自定义路径）
- **别名 (Key Alias)**：`lumina`
- **算法与长度**：RSA 2048-bit
- **主体信息 (DN)**：`CN=Lumina Reader, OU=Mobile, O=Lumina, L=Shenzhen, ST=Guangdong, C=CN`
- **有效期**：2026 年至 2054 年 (10,000 天)

---

## 2. 本地开发与打包

### 本地环境配置
复制 `keystore.properties.template` 为 `keystore.properties` 并填入实际参数：
```properties
storeFile=lumina-release.jks
storePassword=<您的密钥库密码>
keyAlias=lumina
keyPassword=<您的密钥别名密码>
```

### 构建正式 Release APK
```bash
./gradlew assembleRelease
```
- 构建产物路径：`app/build/outputs/apk/release/lumina-reader.apk`

### 校验 APK 签名有效性
```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/lumina-reader.apk
```

### 部署到已连接的真机
```bash
# 安装到手机
adb install -r app/build/outputs/apk/release/lumina-reader.apk

# 启动应用
adb shell am start -n org.lumina.reader/.MainActivity
```

---

## 3. GitHub Actions 自动化发布 (CI/CD)

项目已配置 `.github/workflows/release.yml` 自动化发布流。

### 仓库 Secrets 配置项
在 GitHub 仓库 **Settings -> Secrets and variables -> Actions** 中配置以下 4 个密钥：
1. `KEYSTORE_BASE64`：JKS 文件的 Base64 字符串（通过 `base64 -w 0 lumina-release.jks` 生成）
2. `KEYSTORE_PASSWORD`：密钥库密码
3. `KEY_ALIAS`：密钥别名（如 `lumina`）
4. `KEY_PASSWORD`：密钥密码

### 触发发布
每次为代码打上版本号 Tag（如 `v1.0.0`）并推送到 GitHub 时，Actions 将自动编译、签名并发布到 GitHub Releases 供用户下载：
```bash
git push origin develop
git push origin v1.0.0
```
