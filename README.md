# Smart-Campus-Delivery

> 智慧园区即时零售与履约配送平台 · 后端服务

面向高校园区与封闭科技园区的一体化即时零售后端系统。园区内商户完成类目与商品上架，
用户通过小程序浏览、下单与支付，商户端实时接单并完成履约，同时沉淀经营数据报表。
服务端基于 **Spring Boot + MyBatis + Redis + MySQL** 构建，管理端与用户端通过两套相互隔离的
无状态 JWT 令牌对外提供 API。

---

## 一、项目定位

高校园区与封闭科技园区的即时零售场景有三个显著特征：**下单时段高度重合**（午市、晚市）、
**履约半径短**（园区内配送）、**商品查询读多写少**。这些特征对后端提出的核心诉求是明确的：

| 业务诉求 | 对应的技术设计 |
| --- | --- |
| 高峰时段商品查询并发高 | 类目与 SKU 列表读 Redis 缓存，写操作主动失效，保证缓存与数据库最终一致 |
| 下单不能超卖 | 库存扣减走数据库原子条件更新，任一项不足则由事务整体回滚 |
| 订单状态不能错乱 | 全链路订单状态机，所有流转采用 CAS 乐观更新，并发请求只有一个能成功 |
| 商户要即时感知新单 | WebSocket 长连接推送来单提醒与客户催单，由前端完成语音播报 |

### 当前实现范围

当前版本实现的是**园区自营（单一经营主体）**的完整交易与履约闭环：由管理端统一维护
类目、商品与组合套餐，并承接全部订单履约。多商户入驻（商户注册、租户数据隔离、分账结算）
属于后续扩展方向，当前代码中未实现，也未预留租户字段，因此 README 与面试材料中不对此做超额描述。

---

## 二、技术选型

| 分类 | 技术 | 版本 | 用途 |
| --- | --- | --- | --- |
| 基础框架 | Spring Boot | 2.7.3 | 应用容器与自动装配 |
| Web 层 | Spring MVC | 5.3.x | RESTful 接口、拦截器、全局异常处理 |
| 持久层 | MyBatis + MyBatis-Spring-Boot | 2.2.0 | ORM 映射与动态 SQL |
| 分页 | PageHelper | 1.3.0 | 物理分页 |
| 数据库 | MySQL | 8.0 | 业务数据存储 |
| 连接池 | Druid | 1.2.1 | 数据源管理 |
| 缓存 | Spring Data Redis | 2.7.x | 商品与套餐缓存、营业状态、分布式锁 |
| 缓存抽象 | Spring Cache | 2.7.x | 套餐查询的声明式缓存 |
| 认证 | JJWT | 0.9.1 | JWT 令牌签发与校验 |
| AOP | Spring AOP + AspectJ | 1.9.4 | 公共字段自动填充 |
| 实时通信 | WebSocket (JSR-356) | - | 来单提醒、客户催单 |
| 定时任务 | Spring Task | - | 超时订单自动取消、派送中订单补偿 |
| 报表 | Apache POI (SXSSF) | 3.16 | 运营数据 Excel 导出 |
| 对象存储 | 阿里云 OSS SDK | 3.10.2 | 商品图片上传 |
| 接口文档 | Knife4j | 3.0.2 | 在线接口文档 |
| 构建 | Maven 多模块 | 3.6+ | 依赖与版本统一管理 |

---

## 三、核心架构

### 3.1 模块划分

```
smart-campus-delivery
├── scd-common     通用能力：常量、请求上下文、异常体系、工具类、统一返回结果、配置属性
├── scd-pojo       数据模型：Entity / DTO / VO
└── scd-server     服务实现：Controller / Service / Mapper / 配置 / 拦截器 / 切面 / 定时任务 / WebSocket
```

### 3.2 请求处理链路

```
HTTP 请求
   │
   ├─ JwtTokenAdminInterceptor   校验管理端令牌（token 头），写入 BaseContext
   ├─ JwtTokenUserInterceptor    校验用户端令牌（authentication 头），写入 BaseContext
   │
   ▼
Controller   参数接收、日志、统一返回 Result<T>
   │
   ▼
Service      业务编排、事务边界、缓存注解
   │
   ├─ AutoFillAspect           反射填充 createTime / updateTime / createUser / updateUser
   │
   ▼
Mapper       MyBatis 注解 + XML 动态 SQL
   │
   ▼
MySQL / Redis
```

### 3.3 双端隔离

管理端与用户端使用**不同的签名密钥与请求头名称**，从协议层面隔离，互不可伪造：

| 端 | 路径前缀 | 请求头 | 签名密钥 | 上下文主体 |
| --- | --- | --- | --- | --- |
| 管理端 | `/admin/**` | `token` | `scd.jwt.admin-secret-key` | 员工 id |
| 用户端 | `/user/**` | `authentication` | `scd.jwt.user-secret-key` | 用户 id |

---

## 四、核心业务模块

### 4.1 类目与 SKU / 组合套餐管理

- **类目**：支持菜品分类与套餐分类两类，维护排序与启停状态；删除前校验是否被商品或套餐引用。
- **SKU（菜品）**：商品基础信息 + 口味规格（如辣度、忌口），支持按类目分页检索、批量删除、
  起售停售与图片上传。
- **组合套餐**：套餐由若干 SKU 按份数组合而成，维护套餐与商品的关联关系，
  起售前校验套餐内商品是否全部处于起售状态。
- **Redis 高频读缓存**：用户端按类目查询商品时优先读 Redis，未命中再回源数据库并回写；
  商品与套餐的新增、修改、删除、起停售均主动清理缓存，保证**最终一致性**。
- **缓存三防**：
  - 穿透 → 空结果写入空值缓存（TTL 60s），避免非法类目 id 反复打到数据库；
  - 击穿 → 回源前先抢 Redis 分布式互斥锁（`SET NX EX` + UUID 持有者标识 + Lua 原子释放），
    拿到锁后双重检查，其余线程短暂自旋；自旋超时降级回源保证可用性；
  - 雪崩 → 正常数据 TTL 叠加 0~300s 随机扰动，避免大批 key 集中过期。

### 4.2 客户端无状态认证与请求上下文隔离

- **双 Token / JWT 无状态认证**：登录成功后由服务端签发 JWT（HS256），令牌内仅携带主体 id 与过期时间，
  服务端不保存会话；两端使用独立密钥与请求头，拦截器在 `preHandle` 中校验并解析。
- **ThreadLocal 请求上下文**：解析出的当前登录主体写入 `BaseContext`（内部持有 `ThreadLocal<Long>`），
  Controller 与 Service 无需层层透传用户 id；拦截器在 `afterCompletion` 中调用 `remove()` 清理，
  避免 Tomcat 线程复用导致的内存泄漏与**身份串号**。
- **公共字段自动填充**：自定义 `@AutoFill(OperationType)` 注解 + AOP 切面，
  切点为 `execution(* com.smartcampus.mapper.*.*(..)) && @annotation(...)`，
  通过反射统一写入 `createTime / updateTime / createUser / updateUser`，消除各业务层的重复赋值。

### 4.3 全链路订单状态机与超时补偿

订单状态：`待付款 → 待接单 → 已接单 → 派送中 → 已完成`，另有 `已取消` 分支。

- **状态流转**：用户下单、支付回调、商户接单 / 拒单 / 取消、派送、完成，每一步都做前置状态校验。
- **CAS 乐观更新**：所有状态变更均以「期望状态」作为 SQL 的 `where` 条件
  （`update orders set status = ? where id = ? and status = ?`），
  通过影响行数判断本次流转是否生效。这样「用户取消」与「商户接单」并发到达时只有一个能成功，
  不会出现状态互相覆盖；支付回调也因此具备幂等性。
- **库存扣减与回滚**：下单时对购物车中每件商品执行
  `update dish set stock = stock - n where id = ? and stock >= n`，
  把库存充足性判断下推到 SQL 条件，由数据库行锁保证判断与扣减的原子性；
  任一项不足即抛业务异常，由 `@Transactional` 保证已扣减的库存整体回滚。
  订单取消 / 拒单 / 商户取消时同步回滚库存。
- **超时自动取消**：`@Scheduled` 每分钟扫描「待付款且下单时间超过 15 分钟」的订单并自动取消，
  同样以 CAS 方式更新，避免与用户支付并发时误取消已支付订单。
- **派送补偿闭环**：每日凌晨扫描「派送中超过 1 小时仍未完成」的订单，自动置为已完成，
  形成履约链路的兜底闭环。

### 4.4 商户端实时订单推送与催单分发

- 基于 JSR-356 WebSocket，商户端通过 `/ws/{sid}` 建立长连接，连接会话由服务端统一维护。
- **来单提醒**：支付成功回调中向全部在线会话推送 `{type: 1, orderId, content}`，
  前端据此触发语音播报。
- **客户催单**：用户触发催单接口时推送 `{type: 2, orderId, content}`，
  商户端按消息类型分发到不同的语音提示音。
- 会话容器使用 `ConcurrentHashMap`，保证多线程下的安全读写。

### 4.5 报表数据聚合与流式 Excel 导出

- **数据聚合**：基于 MyBatis 动态 SQL 完成营业额、用户量、订单量与销量 Top10 的按日聚合，
  统计结果以逗号分隔的字符串序列返回，便于前端直接绘制折线图。
- **流式导出**：使用 Apache POI 的 `SXSSFWorkbook`（滑动窗口）生成 Excel，
  内存中仅保留最近 100 行，其余行即时刷写临时文件，避免报表数据量增长时占用大量堆内存；
  导出完成后调用 `dispose()` 清理临时文件。报表包含「概览数据」与「明细数据」两个 Sheet。

---

## 五、工程结构

```
smart-campus-delivery
├── pom.xml                            聚合 POM（统一依赖与插件版本管理）
├── README.md
├── RESUME_GUIDE.md                    简历描述与面试要点
├── scd-common
│   └── src/main/java/com/smartcampus
│       ├── constant/                  常量：JWT 声明、状态、消息、自动填充字段名
│       ├── context/BaseContext        ThreadLocal 请求上下文
│       ├── enumeration/               枚举：OperationType
│       ├── exception/                 业务异常体系
│       ├── json/                      Jackson 时间类型序列化配置
│       ├── properties/                配置属性绑定（JWT / OSS / 微信）
│       ├── result/                    统一返回结果 Result / PageResult
│       └── utils/                     JWT / HttpClient / OSS / 微信支付工具
├── scd-pojo
│   └── src/main/java/com/smartcampus
│       ├── dto/                       数据传输对象
│       ├── entity/                    数据库实体
│       └── vo/                        视图对象
└── scd-server
    └── src/main
        ├── java/com/smartcampus
        │   ├── SmartCampusDeliveryApplication.java  启动类
        │   ├── annocation/            自定义注解 @AutoFill
        │   ├── aspect/                AOP 切面（公共字段自动填充）
        │   ├── config/                MVC / Redis / WebSocket / OSS 配置
        │   ├── controller/admin|user  管理端与用户端控制器
        │   ├── handler/               全局异常处理器
        │   ├── interceptor/           双端 JWT 拦截器
        │   ├── lock/                  Redis 分布式互斥锁
        │   ├── mapper/                MyBatis Mapper 接口
        │   ├── service/impl/          业务实现
        │   ├── task/                  定时任务
        │   └── websocket/             WebSocket 服务端
        ├── resources
        │   ├── application.yml        主配置
        │   ├── application-dev.example.yml  本地配置模板
        │   ├── mapper/*.xml           MyBatis 映射文件
        │   └── sql/                   建表脚本与增量升级脚本
        └── test/java/com/smartcampus  单元测试
```

---

## 六、本地启动

### 6.1 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 8 / 11 / 17 |
| Maven | 3.6+ |
| MySQL | 8.0 |
| Redis | 6.x |

### 6.2 导入 SQL

```bash
# 全新数据库：直接导入完整脚本（包含库存字段与初始化数据）
mysql -uroot -p < scd-server/src/main/resources/sql/smart_campus_delivery.sql

# 已有旧版数据库：只执行增量升级脚本
mysql -uroot -p smart_campus_delivery < scd-server/src/main/resources/sql/upgrade-20261011-add-stock.sql
```

脚本会创建 `smart_campus_delivery` 库、全部数据表与初始数据。默认管理员账号：

| 用户名 | 密码 |
| --- | --- |
| `admin` | `123456` |

> 注意：`smart_campus_delivery.sql` 开头包含 `DROP TABLE`，重复执行会清空已有数据。

### 6.3 配置本地参数

真实的数据库密码、OSS AccessKey、微信凭据等敏感配置**不纳入版本控制**。
请复制模板并填写自己的凭据：

```bash
cd scd-server/src/main/resources
cp application-dev.example.yml application-dev.yml
# 编辑 application-dev.yml，填写 MySQL 密码、Redis 连接、OSS 与微信小程序凭据
```

### 6.4 启动 Redis

```bash
redis-server
```

### 6.5 编译与启动

```bash
# 编译
mvn clean compile

# 方式一：Maven 直接运行
mvn spring-boot:run -pl scd-server

# 方式二：打包后运行
mvn clean package -DskipTests
java -jar scd-server/target/scd-server-1.0-SNAPSHOT.jar
```

默认监听 `8080` 端口，启动成功后控制台输出 `server started`。
若端口被占用，可通过命令行参数覆盖：`java -jar ... --server.port=8081`。

### 6.6 访问接口文档

浏览器打开 <http://localhost:8080/doc.html>。

---

## 七、接口分组

接口文档由 Knife4j 依据 `@Api` 注解自动生成，按业务分组：

| 分组 | 路径前缀 | 说明 |
| --- | --- | --- |
| 员工管理 | `/admin/employee` | 登录、员工增删改查、密码修改 |
| 类目管理 | `/admin/category` | 类目增删改查、启停 |
| 商品管理 | `/admin/dish` | 商品增删改查、起停售、文件上传 |
| 套餐管理 | `/admin/setmeal` | 套餐增删改查、起停售、套餐商品关联 |
| 订单管理 | `/admin/order` | 订单检索、状态统计、接单 / 拒单 / 派送 / 完成 |
| 数据统计 | `/admin/report` | 营业额、用户、订单、销量 Top10、报表导出 |
| 店铺管理 | `/admin/shop` | 营业状态设置与查询 |
| 用户登录 | `/user/user` | 微信登录 |
| 商品浏览 | `/user/dish`、`/user/setmeal` | 按类目浏览商品与套餐（走缓存） |
| 购物车 | `/user/shoppingCart` | 添加、查询、减少、清空 |
| 地址簿 | `/user/addressBook` | 收货地址增删改查、默认地址 |
| 用户订单 | `/user/order` | 下单、支付、历史订单、取消、催单 |

统一返回结构：

```json
{ "code": 1, "msg": null, "data": {} }
```

`code = 1` 表示成功，`code = 0` 表示业务失败，失败原因在 `msg` 中。

---

## 八、单元测试

```bash
mvn test
```

当前共 14 个用例，全部通过：

| 测试类 | 用例数 | 覆盖内容 |
| --- | --- | --- |
| `OrderServiceImplTest` | 7 | 库存不足拒绝下单且不落单、按商品/套餐维度扣减库存、订单状态 CAS 失败抛异常、取消订单的退款与库存回滚 |
| `BaseContextTest` | 3 | ThreadLocal 存取、`remove()` 清理、跨线程隔离 |
| `JwtUtilTest` | 4 | 令牌签发与解析、错误密钥拒绝、过期拒绝、篡改拒绝 |

测试通过 `FakeMapperFactory`（基于 JDK 动态代理）构造 Mapper 测试替身，
不引入 Mockito 等字节码增强框架，因此在 JDK 8 ~ 26 下均可稳定运行。

---

## 九、已知边界

以下内容当前**未实现**，README 与面试表述中不做声称：

- 多商户入驻与租户数据隔离、分账结算
- 真实微信登录与微信支付（当前支付通道在缺少商户证书时降级为模拟支付，便于本地跑通全流程）
- 缓存与数据库的延迟双删 / binlog 异步失效（当前采用「先更新数据库再删除缓存 + 短 TTL」）
- 商品库存为单仓库存模型，未实现多仓与库存预占
