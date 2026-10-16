# 天坛三维可视化系统 Linux 部署教程

本文以当前工程为准，介绍从 Windows 开发机打包，到 Linux 服务器 `/epfs/TianTanSystem` 目录运行系统的完整流程。

当前工程包含：

- `frontendcg`：Vue 3 + Vite 前端
- `backend/data-service`：数据服务，端口 `8081`
- `backend/scheduling-service`：LOD 调度服务，端口 `8083`
- `backend/gateway-service`：API 网关，端口 `8080`
- MySQL：业务数据库

推荐生产访问链路：

```text
浏览器
  │
  ▼
Nginx :80
  ├── /              -> /epfs/TianTanSystem/frontend/dist
  ├── /api/data      -> Gateway :8080
  └── /api/scheduling -> Gateway :8080
                              ├── Data Service :8081
                              └── Scheduling Service :8083
```

## 一、部署目录

服务器使用以下目录：

```text
/epfs/TianTanSystem/
├── app/
│   ├── data-service.jar
│   ├── gateway-service.jar
│   └── scheduling-service.jar
├── frontend/
│   └── dist/
├── data/
│   ├── models/
│   ├── pointclouds/
│   ├── gaussians/
│   └── design-images/
├── tmp/
│   ├── data-service/
│   └── gateway/
├── logs/
│   ├── data-service/
│   ├── gateway/
│   └── scheduling-service/
├── config/
└── run/
```

目录用途：

| 目录 | 用途 |
|---|---|
| `app` | Spring Boot 可执行 jar |
| `frontend/dist` | 前端生产构建文件 |
| `data/models` | 三维模型、GLB、八叉树文件 |
| `data/pointclouds` | Potree 点云数据 |
| `data/gaussians` | RAD、environment.ply 等高斯数据 |
| `data/design-images` | 设计图原图和缩略图 |
| `tmp` | 上传中转、ZIP 解压、Multipart 临时文件 |
| `logs` | 三个后端服务日志 |
| `config` | 生产环境变量文件、Nginx 配置备份等 |
| `run` | 可选，保存 PID 或运行时文件 |

## 二、开发机环境要求

在开发机上确认以下命令可用：

```powershell
java -version
mvn -version
node -v
pnpm -v
```

建议版本：

- JDK 17 或更高版本
- Maven 3.8+
- Node.js 20+
- pnpm

进入项目目录：

```powershell
cd D:\Projects\chonggou\Tiantan
```

## 三、开发机打包前检查

### 1. 检查后端数据库配置

打开：

```text
backend/data-service/src/main/resources/application.yml
```

当前开发环境默认配置为：

```text
数据库地址：jdbc:mysql://localhost:3306/tiantan_db
数据根目录：D:\TiantanData
临时目录：D:\TiantanTemp
```

生产环境不建议把真实数据库密码写死在 jar 或代码仓库中。生产启动时使用环境变量覆盖：

```bash
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
```

### 2. 检查前端 API 访问方式

生产环境前端通过 Nginx 访问 `/api`，不需要把后端地址写入前端代码。

前端请求形如：

```text
/api/data/...
/api/scheduling/...
```

Nginx 将这些请求转发到 Gateway `8080`。

### 3. 检查工作区状态

```powershell
git status
```

确认需要部署的代码已经保存，尤其是：

- `frontendcg`
- `backend/data-service`
- `backend/gateway-service`
- `backend/scheduling-service`

## 四、构建前端

进入当前使用的前端工程：

```powershell
cd D:\Projects\chonggou\Tiantan\frontendcg
```

安装依赖：

```powershell
pnpm install --frozen-lockfile
```

执行生产构建：

```powershell
pnpm build
```

构建成功后，文件位于：

```text
D:\Projects\chonggou\Tiantan\frontendcg\dist
```

检查构建结果：

```powershell
Get-ChildItem .\dist
```

如果需要在开发机本地预览生产构建：

```powershell
pnpm preview --host 0.0.0.0
```

## 五、构建后端

回到项目根目录：

```powershell
cd D:\Projects\chonggou\Tiantan
```

先运行数据服务测试：

```powershell
mvn -f backend\data-service\pom.xml test
```

再构建三个 Spring Boot 服务：

```powershell
mvn -f backend\pom.xml clean package -DskipTests
```

如果希望打包前再次运行全部测试：

```powershell
mvn -f backend\pom.xml clean package
```

成功后检查 jar：

```powershell
Get-ChildItem backend\data-service\target\*-SNAPSHOT.jar
Get-ChildItem backend\gateway-service\target\*-SNAPSHOT.jar
Get-ChildItem backend\scheduling-service\target\*-SNAPSHOT.jar
```

应得到：

```text
backend/data-service/target/data-service-1.0.0-SNAPSHOT.jar
backend/gateway-service/target/gateway-service-1.0.0-SNAPSHOT.jar
backend/scheduling-service/target/scheduling-service-1.0.0-SNAPSHOT.jar
```

## 六、准备部署文件

建议在开发机创建一个临时发布目录：

```powershell
$release = "D:\Projects\chonggou\Tiantan\release"
New-Item -ItemType Directory -Force "$release\frontend\dist" | Out-Null
New-Item -ItemType Directory -Force "$release\app" | Out-Null
```

复制前端：

```powershell
Copy-Item "D:\Projects\chonggou\Tiantan\frontendcg\dist\*" "$release\frontend\dist" -Recurse -Force
```

复制后端：

```powershell
Copy-Item "D:\Projects\chonggou\Tiantan\backend\data-service\target\data-service-1.0.0-SNAPSHOT.jar" "$release\app\data-service.jar" -Force
Copy-Item "D:\Projects\chonggou\Tiantan\backend\gateway-service\target\gateway-service-1.0.0-SNAPSHOT.jar" "$release\app\gateway-service.jar" -Force
Copy-Item "D:\Projects\chonggou\Tiantan\backend\scheduling-service\target\scheduling-service-1.0.0-SNAPSHOT.jar" "$release\app\scheduling-service.jar" -Force
```

打包发布目录：

```powershell
Compress-Archive -Path "$release\*" -DestinationPath "D:\Projects\chonggou\Tiantan\tiantan-release.zip" -Force
```

## 七、准备 Linux 服务器

以下命令以 Ubuntu/Debian 为例，服务器账号假设为 `tiantan`。

### 1. 安装基础软件

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless nginx unzip rsync curl
```

确认 Java：

```bash
java -version
```

服务器只运行已经构建好的前端和 jar，不需要安装 Node.js、pnpm 或 Maven。

### 2. 创建运行账号

如果服务器还没有专用账号：

```bash
sudo useradd --system --create-home --shell /usr/sbin/nologin tiantan
```

如果账号已存在，跳过此命令。

### 3. 创建目录

```bash
sudo mkdir -p \
  /epfs/TianTanSystem/app \
  /epfs/TianTanSystem/frontend/dist \
  /epfs/TianTanSystem/data/models \
  /epfs/TianTanSystem/data/pointclouds \
  /epfs/TianTanSystem/data/gaussians \
  /epfs/TianTanSystem/data/design-images \
  /epfs/TianTanSystem/tmp/data-service \
  /epfs/TianTanSystem/tmp/gateway \
  /epfs/TianTanSystem/logs/data-service \
  /epfs/TianTanSystem/logs/gateway \
  /epfs/TianTanSystem/logs/scheduling-service \
  /epfs/TianTanSystem/config \
  /epfs/TianTanSystem/run
```

授权：

```bash
sudo chown -R tiantan:tiantan /epfs/TianTanSystem
sudo chmod -R u+rwX /epfs/TianTanSystem
```

注意：模型、点云和高斯文件可能很大，确认 `/epfs` 有足够磁盘空间：

```bash
df -h /epfs
```

## 八、上传发布文件

在开发机 PowerShell 中执行。将 `user` 替换为 Linux 用户名，将 `SERVER_IP` 替换为服务器 IP：

```powershell
scp D:\Projects\chonggou\Tiantan\tiantan-release.zip user@SERVER_IP:/tmp/
```

登录服务器：

```powershell
ssh user@SERVER_IP
```

在服务器解压：

```bash
sudo unzip -o /tmp/tiantan-release.zip -d /epfs/TianTanSystem/
sudo chown -R tiantan:tiantan /epfs/TianTanSystem
```

如果压缩包内没有直接对应到 `app`、`frontend/dist`，可以使用 rsync 分开上传：

```powershell
scp -r D:\Projects\chonggou\Tiantan\frontendcg\dist\* user@SERVER_IP:/tmp/tiantan-frontend/
scp D:\Projects\chonggou\Tiantan\backend\data-service\target\data-service-1.0.0-SNAPSHOT.jar user@SERVER_IP:/tmp/data-service.jar
scp D:\Projects\chonggou\Tiantan\backend\gateway-service\target\gateway-service-1.0.0-SNAPSHOT.jar user@SERVER_IP:/tmp/gateway-service.jar
scp D:\Projects\chonggou\Tiantan\backend\scheduling-service\target\scheduling-service-1.0.0-SNAPSHOT.jar user@SERVER_IP:/tmp/scheduling-service.jar
```

服务器上执行：

```bash
sudo cp /tmp/data-service.jar /epfs/TianTanSystem/app/data-service.jar
sudo cp /tmp/gateway-service.jar /epfs/TianTanSystem/app/gateway-service.jar
sudo cp /tmp/scheduling-service.jar /epfs/TianTanSystem/app/scheduling-service.jar
sudo cp -r /tmp/tiantan-frontend/* /epfs/TianTanSystem/frontend/dist/
sudo chown -R tiantan:tiantan /epfs/TianTanSystem
```

## 九、配置 MySQL

### 1. 创建数据库

如果使用服务器本机 MySQL：

```bash
sudo apt install -y mysql-server
sudo systemctl enable --now mysql
```

进入 MySQL：

```bash
sudo mysql
```

创建数据库和应用账号：

```sql
CREATE DATABASE IF NOT EXISTS tiantan_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'tiantan'@'127.0.0.1'
  IDENTIFIED BY '请替换为强密码';

GRANT ALL PRIVILEGES ON tiantan_db.* TO 'tiantan'@'127.0.0.1';
FLUSH PRIVILEGES;
EXIT;
```

不要执行以下命令：

```sql
DROP DATABASE tiantan_db;
DROP TABLE ...
TRUNCATE TABLE ...
```

这样可以保留现有已经入库的数据。

### 2. 如果已有生产数据库

不要重新创建或清空数据库，只确认：

```bash
mysql -h DB_HOST -u tiantan -p -e "SELECT 1"
```

应用启动时使用 `ddl-auto: update`，会补充缺少的表结构，但不会主动删除已有业务数据。

## 十、创建生产环境变量

在服务器创建：

```bash
sudo nano /epfs/TianTanSystem/config/tiantan.env
```

内容示例：

```bash
SPRING_PROFILES_ACTIVE=prod

SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/tiantan_db?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
SPRING_DATASOURCE_USERNAME=tiantan
SPRING_DATASOURCE_PASSWORD=请替换为强密码

TIANTAN_STORAGE_ROOT=/epfs/TianTanSystem/data
TIANTAN_STORAGE_TEMP=/epfs/TianTanSystem/tmp

TIANTAN_DATA_SERVICE_PORT=8081
TIANTAN_GATEWAY_PORT=8080
TIANTAN_SCHEDULING_PORT=8083

TIANTAN_DATA_SERVICE_URL=http://127.0.0.1:8081
TIANTAN_SCHEDULING_SERVICE_URL=http://127.0.0.1:8083

# 如果使用 Nginx 直接暴露文件，可填写公开资源地址；
# 当前推荐由数据服务接口读取，因此可以留空。
TIANTAN_STORAGE_PUBLIC_BASE=

TIANTAN_MAX_UPLOAD_SIZE=20GB
TIANTAN_MAX_REQUEST_SIZE=20GB
```

保护密码文件：

```bash
sudo chown root:tiantan /epfs/TianTanSystem/config/tiantan.env
sudo chmod 640 /epfs/TianTanSystem/config/tiantan.env
```

## 十一、先手动启动后端验证

先启动数据服务：

```bash
sudo -u tiantan bash -c '
  set -a
  . /epfs/TianTanSystem/config/tiantan.env
  set +a
  exec java -jar /epfs/TianTanSystem/app/data-service.jar \
    --spring.profiles.active=prod
'
```

这会占用当前终端。看到类似以下日志后表示启动完成：

```text
Tomcat started on port 8081
Started ModelDataApplication
```

另开一个 SSH 终端，启动调度服务：

```bash
sudo -u tiantan bash -c '
  set -a
  . /epfs/TianTanSystem/config/tiantan.env
  set +a
  exec java -jar /epfs/TianTanSystem/app/scheduling-service.jar
'
```

再开一个 SSH 终端，启动网关：

```bash
sudo -u tiantan bash -c '
  set -a
  . /epfs/TianTanSystem/config/tiantan.env
  set +a
  exec java -jar /epfs/TianTanSystem/app/gateway-service.jar \
    --spring.profiles.active=prod
'
```

检查端口：

```bash
ss -lntp | grep -E ':8080|:8081|:8083'
```

检查接口：

```bash
curl -i http://127.0.0.1:8081/api/data/models/list
curl -i http://127.0.0.1:8080/api/data/models/list
curl -i http://127.0.0.1:8080/api/data/point-clouds/list
curl -i http://127.0.0.1:8080/api/data/gaussians/list
curl -i http://127.0.0.1:8080/api/data/design-images/list
```

如果接口返回 JSON，说明后端基本正常。

## 十二、配置 systemd 开机启动

### 1. 数据服务

创建：

```bash
sudo nano /etc/systemd/system/tiantan-data.service
```

内容：

```ini
[Unit]
Description=Tiantan Data Service
After=network.target mysql.service
Wants=mysql.service

[Service]
Type=simple
User=tiantan
Group=tiantan
WorkingDirectory=/epfs/TianTanSystem
EnvironmentFile=/epfs/TianTanSystem/config/tiantan.env
ExecStart=/usr/bin/java -Xms512m -Xmx4g -jar /epfs/TianTanSystem/app/data-service.jar --spring.profiles.active=prod
Restart=on-failure
RestartSec=10
SuccessExitStatus=143
LimitNOFILE=65535
StandardOutput=append:/epfs/TianTanSystem/logs/data-service/service.log
StandardError=append:/epfs/TianTanSystem/logs/data-service/service-error.log

[Install]
WantedBy=multi-user.target
```

### 2. 调度服务

创建：

```bash
sudo nano /etc/systemd/system/tiantan-scheduling.service
```

内容：

```ini
[Unit]
Description=Tiantan Scheduling Service
After=network.target tiantan-data.service
Wants=tiantan-data.service

[Service]
Type=simple
User=tiantan
Group=tiantan
WorkingDirectory=/epfs/TianTanSystem
EnvironmentFile=/epfs/TianTanSystem/config/tiantan.env
ExecStart=/usr/bin/java -Xms256m -Xmx2g -jar /epfs/TianTanSystem/app/scheduling-service.jar
Restart=on-failure
RestartSec=10
SuccessExitStatus=143
LimitNOFILE=65535
StandardOutput=append:/epfs/TianTanSystem/logs/scheduling-service/service.log
StandardError=append:/epfs/TianTanSystem/logs/scheduling-service/service-error.log

[Install]
WantedBy=multi-user.target
```

### 3. 网关服务

创建：

```bash
sudo nano /etc/systemd/system/tiantan-gateway.service
```

内容：

```ini
[Unit]
Description=Tiantan Gateway Service
After=network.target tiantan-data.service tiantan-scheduling.service
Wants=tiantan-data.service tiantan-scheduling.service

[Service]
Type=simple
User=tiantan
Group=tiantan
WorkingDirectory=/epfs/TianTanSystem
EnvironmentFile=/epfs/TianTanSystem/config/tiantan.env
ExecStart=/usr/bin/java -Xms256m -Xmx2g -jar /epfs/TianTanSystem/app/gateway-service.jar --spring.profiles.active=prod
Restart=on-failure
RestartSec=10
SuccessExitStatus=143
LimitNOFILE=65535
StandardOutput=append:/epfs/TianTanSystem/logs/gateway/service.log
StandardError=append:/epfs/TianTanSystem/logs/gateway/service-error.log

[Install]
WantedBy=multi-user.target
```

### 4. 启用服务

```bash
sudo systemctl daemon-reload
sudo systemctl enable tiantan-data.service
sudo systemctl enable tiantan-scheduling.service
sudo systemctl enable tiantan-gateway.service
```

启动：

```bash
sudo systemctl start tiantan-data.service
sudo systemctl start tiantan-scheduling.service
sudo systemctl start tiantan-gateway.service
```

查看状态：

```bash
sudo systemctl status tiantan-data.service
sudo systemctl status tiantan-scheduling.service
sudo systemctl status tiantan-gateway.service
```

查看实时日志：

```bash
sudo journalctl -u tiantan-data.service -f
sudo journalctl -u tiantan-scheduling.service -f
sudo journalctl -u tiantan-gateway.service -f
```

重启服务：

```bash
sudo systemctl restart tiantan-data.service
sudo systemctl restart tiantan-scheduling.service
sudo systemctl restart tiantan-gateway.service
```

更新 jar 后建议按以下顺序重启：

```bash
sudo systemctl restart tiantan-data.service
sudo systemctl restart tiantan-scheduling.service
sudo systemctl restart tiantan-gateway.service
```

## 十三、配置 Nginx

创建站点配置：

```bash
sudo nano /etc/nginx/sites-available/tiantan
```

内容：

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 80;
    listen [::]:80;
    server_name _;

    root /epfs/TianTanSystem/frontend/dist;
    index index.html;

    client_max_body_size 20G;
    send_timeout 3600s;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_request_buffering off;
        proxy_buffering off;
    }

    location /api/scheduling/ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
}
```

启用配置：

```bash
sudo ln -s /etc/nginx/sites-available/tiantan /etc/nginx/sites-enabled/tiantan
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx
```

注意：`location /api/scheduling/ws` 要放在 `location /api/` 前面或使用更精确匹配，避免 WebSocket 请求被普通 HTTP 代理规则覆盖。若 Nginx 报重复配置，检查：

```bash
sudo nginx -T
```

## 十四、开放防火墙

如果服务器启用了 UFW，只需要开放 Nginx 端口：

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw enable
sudo ufw status
```

不建议对公网开放 `8080`、`8081`、`8083`，这些端口只应由本机 Nginx 和服务之间访问。

## 十五、最终访问与验证

获取服务器 IP：

```bash
hostname -I
```

浏览器访问：

```text
http://服务器IP/
```

前端加载后依次验证：

1. 首页可以打开。
2. 模型展示页可以获取模型列表。
3. 点云展示页可以获取点云目录。
4. 高斯渲染页可以获取高斯场景。
5. 设计图预览页可以获取设计图列表。
6. 数据管理页可以获取资产和上传任务。
7. 信息统计页可以获取统计数据。
8. 模型加载后，LOD 调度连接正常。
9. 上传一个小文件测试入库路径。

服务器端接口检查：

```bash
curl -i http://127.0.0.1/api/data/models/list
curl -i http://127.0.0.1/api/data/point-clouds/list
curl -i http://127.0.0.1/api/data/gaussians/list
curl -i http://127.0.0.1/api/data/design-images/list
```

检查数据目录：

```bash
find /epfs/TianTanSystem/data -maxdepth 2 -type d -print
du -sh /epfs/TianTanSystem/data/*
```

## 十六、更新版本

### 1. 开发机重新打包

```powershell
cd D:\Projects\chonggou\Tiantan\frontendcg
pnpm install --frozen-lockfile
pnpm build

cd D:\Projects\chonggou\Tiantan
mvn -f backend\pom.xml clean package -DskipTests
```

### 2. 备份服务器配置

```bash
sudo cp /epfs/TianTanSystem/config/tiantan.env /epfs/TianTanSystem/config/tiantan.env.bak.$(date +%Y%m%d%H%M%S)
```

不要删除以下目录：

```text
/epfs/TianTanSystem/data
/epfs/TianTanSystem/config
```

### 3. 上传并替换程序

先上传到临时目录，再替换：

```powershell
scp D:\Projects\chonggou\Tiantan\frontendcg\dist\* user@SERVER_IP:/tmp/tiantan-frontend/
scp D:\Projects\chonggou\Tiantan\backend\data-service\target\data-service-1.0.0-SNAPSHOT.jar user@SERVER_IP:/tmp/data-service.jar
scp D:\Projects\chonggou\Tiantan\backend\gateway-service\target\gateway-service-1.0.0-SNAPSHOT.jar user@SERVER_IP:/tmp/gateway-service.jar
scp D:\Projects\chonggou\Tiantan\backend\scheduling-service\target\scheduling-service-1.0.0-SNAPSHOT.jar user@SERVER_IP:/tmp/scheduling-service.jar
```

服务器上执行：

```bash
sudo systemctl stop tiantan-gateway.service
sudo systemctl stop tiantan-scheduling.service
sudo systemctl stop tiantan-data.service

sudo cp /tmp/data-service.jar /epfs/TianTanSystem/app/data-service.jar
sudo cp /tmp/gateway-service.jar /epfs/TianTanSystem/app/gateway-service.jar
sudo cp /tmp/scheduling-service.jar /epfs/TianTanSystem/app/scheduling-service.jar

sudo rsync -a --delete /tmp/tiantan-frontend/ /epfs/TianTanSystem/frontend/dist/
sudo chown -R tiantan:tiantan /epfs/TianTanSystem/app /epfs/TianTanSystem/frontend

sudo systemctl start tiantan-data.service
sudo systemctl start tiantan-scheduling.service
sudo systemctl start tiantan-gateway.service
sudo systemctl reload nginx
```

`rsync --delete` 只允许用于前端 `dist` 目录，不要对 `/epfs/TianTanSystem/data` 使用，否则会删除已入库数据。

## 十七、备份与恢复

### 1. 数据库备份

```bash
mysqldump \
  -h 127.0.0.1 \
  -u tiantan \
  -p \
  --single-transaction \
  --routines \
  --events \
  tiantan_db > /epfs/TianTanSystem/config/tiantan_db_$(date +%Y%m%d_%H%M%S).sql
```

### 2. 文件数据备份

```bash
sudo tar -czf \
  /epfs/TianTanSystem/config/tiantan_data_$(date +%Y%m%d_%H%M%S).tar.gz \
  -C /epfs/TianTanSystem data
```

大型点云和模型数据可能不适合压缩，可以使用 rsync 同步到备份磁盘：

```bash
rsync -a --info=progress2 \
  /epfs/TianTanSystem/data/ \
  /backup/TianTanSystem/data/
```

### 3. 恢复数据库

```bash
mysql -h 127.0.0.1 -u tiantan -p tiantan_db < /path/to/tiantan_db_backup.sql
```

恢复文件后确认权限：

```bash
sudo chown -R tiantan:tiantan /epfs/TianTanSystem/data
```

## 十八、常见问题排查

### 1. 页面能打开，但接口返回 502

检查：

```bash
sudo systemctl status tiantan-gateway.service
curl -i http://127.0.0.1:8080/api/data/models/list
sudo journalctl -u tiantan-gateway.service -n 100 --no-pager
```

### 2. Gateway 正常，但数据服务启动失败

```bash
sudo systemctl status tiantan-data.service
sudo journalctl -u tiantan-data.service -n 200 --no-pager
```

重点检查：

- MySQL 地址、用户名、密码
- MySQL 端口是否可访问
- `/epfs/TianTanSystem/data` 是否存在
- `/epfs/TianTanSystem/tmp` 是否有写权限
- Java 版本是否满足要求

### 3. 页面能打开，但模型列表为空

```bash
curl -i http://127.0.0.1:8080/api/data/models/list
mysql -h 127.0.0.1 -u tiantan -p -e "USE tiantan_db; SELECT id,name FROM model_info;"
```

确认应用连接的是正确数据库，而不是新建的空数据库。

### 4. 上传大文件失败

检查 Nginx：

```bash
sudo nginx -T | grep client_max_body_size
```

检查环境变量：

```bash
grep -E 'MAX_UPLOAD|MAX_REQUEST|STORAGE' /epfs/TianTanSystem/config/tiantan.env
```

检查临时目录空间：

```bash
df -h /epfs
du -sh /epfs/TianTanSystem/tmp
```

### 5. WebSocket 连接失败

检查：

```bash
sudo nginx -t
sudo journalctl -u tiantan-gateway.service -n 100 --no-pager
ss -lntp | grep -E ':8080|:8083'
```

浏览器开发者工具中检查请求：

```text
/api/scheduling/ws
```

该请求必须返回 WebSocket `101 Switching Protocols`，不能返回普通的 `404`、`400` 或 `502`。

### 6. 服务器重启后服务没有起来

```bash
sudo systemctl is-enabled tiantan-data.service
sudo systemctl is-enabled tiantan-scheduling.service
sudo systemctl is-enabled tiantan-gateway.service
sudo systemctl is-enabled nginx
```

如果没有启用：

```bash
sudo systemctl enable tiantan-data.service
sudo systemctl enable tiantan-scheduling.service
sudo systemctl enable tiantan-gateway.service
sudo systemctl enable nginx
```

### 7. 权限错误

```bash
sudo chown -R tiantan:tiantan /epfs/TianTanSystem
sudo chmod -R u+rwX /epfs/TianTanSystem
```

不要使用：

```bash
chmod -R 777 /epfs/TianTanSystem
```

## 十九、部署完成检查清单

- [ ] `/epfs/TianTanSystem/data` 已确认是正确的数据目录。
- [ ] 已确认不会执行删库、删表、清空数据目录的操作。
- [ ] MySQL 数据库连接正常。
- [ ] 三个 jar 已上传。
- [ ] `frontend/dist` 已上传。
- [ ] `tiantan.env` 已配置。
- [ ] `tiantan-data.service` 正常运行。
- [ ] `tiantan-scheduling.service` 正常运行。
- [ ] `tiantan-gateway.service` 正常运行。
- [ ] Nginx 配置检查通过。
- [ ] 浏览器可以打开首页。
- [ ] `/api/data/models/list` 返回正常。
- [ ] `/api/data/point-clouds/list` 返回正常。
- [ ] `/api/data/gaussians/list` 返回正常。
- [ ] `/api/data/design-images/list` 返回正常。
- [ ] 模型、点云、高斯、设计图页面均可获取数据。
- [ ] 上传测试通过。
- [ ] WebSocket LOD 调度连接通过。
- [ ] 已完成数据库和文件数据备份。

