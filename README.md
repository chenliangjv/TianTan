# Tiantan WebGL

天坛三维可视化系统。前端提供首页、模型展示、点云展示、高斯渲染、设计图预览、数据管理和信息统计。首页以六张功能卡片提供工作台入口。设计图当前支持 PNG/JPG 图片入库与预览，暂不接入 DWG 转换。模型展示保留后端 LOD 调度与病害标注。

## 目录

- `backend`：Spring Boot 多模块后端，仅含 Gateway、数据服务和 LOD 调度服务
- `frontendcg`：Vue 3 + Vite 前端
- `模型数据`：天坛 LOD/GLB 原始数据
- `参考代码`：原始设计参考，不参与构建和运行

## 启动依赖

- Java 17+
- Maven 3.8+
- Node.js 20.19+ 和 pnpm
- MySQL 8（账号、密码按 `backend/data-service/src/main/resources/application.yml` 配置）

数据服务默认连接 `tiantan_db`。连接串启用 `createDatabaseIfNotExist=true`，JPA 使用 `ddl-auto: update`；首次启动会自动创建当前功能对应的数据表，包括模型、模型八叉树/节点/文件、Potree 点云数据集、高斯场景及其资产、设计图像、入库任务以及病害标注/病害点。点云不再按节点或文件逐条建表，数据库只保存数据集元信息和服务器相对目录。已移除的认证、Agent、地质与倾斜摄影模块不再创建表。

## 首次克隆配置

首次克隆仓库后，先从模板创建本地数据库配置：

```powershell
Copy-Item backend/data-service/src/main/resources/application.example.yml backend/data-service/src/main/resources/application.yml
$env:SPRING_DATASOURCE_PASSWORD = "your-local-database-password"
```

可通过 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME` 和 `SPRING_DATASOURCE_PASSWORD` 覆盖连接信息。实际 `application.yml` 已被 Git 忽略，请勿提交真实密码。

仓库提交 `frontendcg`、`backend` 和项目说明文档；旧 `frontend`、依赖目录、构建产物、网关中生成的前端静态文件、本地数据、备份及参考工程均不入库。高斯查看器所需的 Spark 2.2.0 / Three.js 0.180.0 及许可证已保存在 `frontendcg/public/gaussian-viewer`，无需额外克隆 `Pro/spark-main`。

## 启动后端

在 `Tiantan` 目录下分别执行：

```powershell
mvn -f backend\data-service\pom.xml spring-boot:run
mvn -f backend\scheduling-service\pom.xml spring-boot:run
mvn -f backend\gateway-service\pom.xml spring-boot:run
```

端口：Gateway `8080`、数据服务 `8081`、LOD 调度服务 `8083`。

模型和点云数据默认分别存入 `D:\TiantanData\models` 和 `D:\TiantanData\pointclouds`，上传中转目录为 `D:\TiantanTemp`。部署时可通过 `TIANTAN_STORAGE_ROOT`、`TIANTAN_STORAGE_TEMP` 覆盖。

## 数据管理

原 DWG 入库、转换和预览实现已移除，后端不再依赖 Aspose.CAD 或其许可证。已有图形原文件、转换产物和数据库数据保留，本次代码清理不执行数据删除或删表。

前端数据入库页面可选择模型或点云目录，或直接上传 ZIP 压缩包。ZIP 和文件夹上传均使用 multipart 磁盘中转，避免大文件整体进入应用内存；ZIP 在服务器临时目录流式解压后提交异步入库任务。

点云仅接受 Potree 2.0 数据集，根目录或三层子目录内必须同时包含 `metadata.json`、`hierarchy.bin`、`octree.bin`。入库后该三文件保存在 `storage.root-path/pointclouds/<storageKey>`，数据库只记录数据集元信息和相对目录。点云展示页通过 HTTP Range 按需读取 `hierarchy.bin`、`octree.bin`，不会一次性向浏览器下载完整八叉树文件。对应接口：

```text
POST /api/data/models/ingest?dataPath=<服务端可访问的模型目录>
POST /api/data/point-clouds/ingest?dataPath=<服务端可访问的点云目录>
POST /api/data/models/upload-archive (multipart 字段 `archive`，ZIP 压缩包)
POST /api/data/point-clouds/upload-archive (multipart 字段 `archive`，ZIP 压缩包)
GET  /api/data/tasks/<taskId>
GET  /api/data/statistics
```

默认单个压缩包和单次请求上限均为 `20GB`。部署环境可使用 `TIANTAN_MAX_UPLOAD_SIZE`、`TIANTAN_MAX_REQUEST_SIZE` 调整；`TIANTAN_STORAGE_TEMP` 和 `TIANTAN_GATEWAY_UPLOAD_TEMP` 可指定大容量临时磁盘。

模型入库完成后，模型展示页从模型列表加载模型，并通过 `/api/scheduling/ws` 使用后端视锥体 LOD 调度。点云入库完成后，在“点云展示”选择数据集即可使用 Three.js + Potree 进行真实点云渲染。

## 设计图图像入库与预览

更新代码后需要重启数据服务（8081），JPA 会自动创建 `design_image` 表。在“数据管理”选择设计图类型上传，或在“设计图预览”上传本地 PNG/JPG/JPEG 文件。`frontendcg/public/assets/yq1.png` 为随前端保存的图像资源；打开页面不会自动将其入库。

每批最多 20 张，单张最大 20 MB、4000 万像素；后端校验实际文件格式并按 SHA-256 内容去重。数据库保存名称、格式、尺寸、大小、内容哈希、存储标识和入库时间；原件及 360 像素以内的缩略图保存在 `${storage.root-path}/design-images/<UUID>/`，默认根目录为 `D:/TiantanData`。批次逐张处理，部分失败不影响成功项。删除操作仅移除入库记录和后端副本，不删除 public 中的样例源文件。

设计图预览支持图纸目录、名称搜索、适应窗口、原始尺寸、以鼠标为中心的滚轮缩放、拖拽、双击复位和全屏；按 `+`/`-` 缩放，按 `0` 适应窗口。当前为普通栅格图像，不提供 CAD 实体选择、精确测量或无限清晰缩放。未来可在独立图像 API 与查看器基础上替换转换/预览链路，无需恢复旧 DWG 实现。

```text
POST   /api/data/design-images/upload  (multipart 字段 files，支持多文件)
GET    /api/data/design-images/list
GET    /api/data/design-images/{id}/content
GET    /api/data/design-images/{id}/thumbnail
DELETE /api/data/design-images/{id}
```

验证：`mvn -f backend/data-service/pom.xml test` 使用内存 H2 和系统临时目录测试图像入库，不连接业务 MySQL；前端在 `frontendcg` 下执行 `pnpm build`。当前前端未配置独立的测试命令。

## 信息统计

更新后需重启数据服务（8081），再打开“信息统计”。本次统计改造不新增数据库表，也不修改已有资产。`GET /api/data/statistics?days=30` 保留原有 `models`、`pointClouds`、`diseases`、`ingestionTasks`、`totals` 字段，并增加 `gaussians`、`designs`、`categories`、`trend`、`updatedAt`、`timezone`；`days` 仅支持 7、30、90，默认 30。

- 当前库全量统计模型、点云、高斯场景和设计图；模型兼容分类为空的旧数据，排除旧 POINT_CLOUD 记录。
- “已归档数据容量”合计模型关联文件、Potree 数据集、高斯主体 RAD 及天空 PLY、设计图原图，不统计临时文件、缩略图和日志，不代表物理磁盘使用量。
- 高斯天空是场景附件，不额外计为场景；原始高斯数与含 LOD 高斯数分别显示、不相加。天空场景数按 SKY 资产是否存在统计，与默认显示开关无关。
- “现存资产入库时间分布”按北京时间每日补零；天数仅筛选此图，不影响全量指标。硬删除会同时移除时间分布中的对应资产，不把它当作历史事件日志。
- 异步任务仅统计 MODEL、POINT_CLOUD、GAUSSIAN 的排队、运行、成功、失败状态；设计图是同步上传，不计入任务图。

后端使用数据库聚合与只读一致性事务，不读取完整文件列表、不扫描 RAD/PLY 文件。`frontendcg` 在进入统计页、手动刷新或切换 7/30/90 天时重新取数。入库或删除后返回统计页即可看到新快照。

回归测试：后端 `StatisticsIntegrationTest` 使用内存 H2，不连接业务 MySQL。前端可在启动后检查 `/stats` 页面及 7/30/90 天筛选。

## 点云颜色控制

在“点云展示 → 场景控制 → 点云颜色”切换原始 RGB 或单色显示。两种模式均支持亮度、对比度和 Gamma，并共用调节参数；单色模式通过取色器或预设色选择基础颜色，再应用调节。默认参数保持原始 RGB 或所选单色不变。纯白等极值颜色对部分参数可能不敏感，可选择中间色观察效果。“恢复默认”仅重置颜色，不改变相机。设置在当前页面内切换、卸载重载数据集时保留，刷新或离开页面后恢复默认，不写入数据库或原始文件。

颜色通过 GPU 材质 uniform 更新，保留原生 Potree LOD 调度和 HTTP Range 加载。适配层针对固定版本 `potree-core 2.0.15` 的 Potree 2.0 RGBA 着色器；升级依赖时需重新验证默认 RGB、单色像素和 EDL。若着色器结构不兼容，禁用调色并保留原生显示。当前前端适配层位于 `frontendcg/src/utils/pointCloudColor.js`。

## 三维视图操作

模型列表的“加载”按钮在加载完成后变为“卸载”；卸载或“清空场景”会清除模型、节点包围盒、构件选择及病害高亮/绘制状态，但不删除数据库或本地保存的病害记录。点云卸载也会一并释放独立的节点包围盒。

模型和点云加载后均支持按住 `W`/`S` 前后移动、`A`/`D` 左右移动、`Q`/`E` 升降。六个方向均基于相机当前姿态计算，前后不会被世界坐标的 Y 轴或数据倾斜影响；移动速度随数据范围和观察距离调整。输入框编辑、病害绘制/录入、窗口失焦及卸载时暂停漫游，鼠标旋转和缩放操作保持不变。

## 启动前端

```powershell
cd frontendcg
pnpm install
pnpm dev
```

开发服务器默认地址为 `http://127.0.0.1:5173/`，端口占用时以 Vite 输出为准。`/api` 及该路径下的 WebSocket 会代理到 Gateway `8080`。生产构建：

```powershell
pnpm build
```

页面路由：`/`、`/model`、`/pointcloud`、`/gaussian`、`/design`、`/manage`、`/stats`。
