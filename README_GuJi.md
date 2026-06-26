# 古籍阅读 - 精简版阅读器

基于Legado开源阅读器定制的古籍阅读应用，专注于中国古典文学阅读。

## 功能特点

### 已精简的功能
- ✅ 移除RSS订阅功能
- ✅ 移除二维码扫描功能
- ✅ 移除内置WebView浏览器
- ✅ 移除音频播放功能
- ✅ 移除漫画阅读功能
- ✅ 移除字典管理功能
- ✅ 移除文件管理功能
- ✅ 移除Web服务功能
- ✅ 移除Firebase统计
- ✅ 移除多图标Launcher

### 保留的核心功能
- 📚 书架管理（添加、删除、整理书籍）
- 🔍 在线搜索（通过内置书源搜索）
- 📖 本地导入（支持TXT、EPUB等格式）
- 📋 书源管理（导入、导出、编辑书源）
- ⚙️ 基本设置

### 内置书源
1. **中国哲学书电子化计划** (ctext.org)
   - 四大名著
   - 儒家经典
   - 道家经典
   - 佛家经典
   - 历史典籍

2. **古诗文网** (gushiwen.cn)
   - 唐诗
   - 宋词
   - 元曲
   - 古文

3. **维基文库** (wikisource.org)
   - 古典文学
   - 公版书籍

## 如何构建

### 环境要求
- Android Studio Arctic Fox 或更高版本
- JDK 17
- Android SDK 36

### 构建步骤
1. 克隆项目
2. 用Android Studio打开项目
3. 等待Gradle同步完成
4. 选择 `app` 模块
5. 点击运行或构建APK

### 生成APK
```bash
# Debug版本
./gradlew assembleDebug

# Release版本（需要配置签名）
./gradlew assembleRelease
```

## 如何使用

### 首次使用
1. 安装APK后打开应用
2. 同意隐私协议
3. 应用会自动加载内置书源
4. 在「发现」页面浏览分类
5. 搜索或浏览添加书籍到书架

### 添加书源
1. 点击底部「我的」
2. 进入「书源管理」
3. 点击右上角菜单
4. 选择「导入书源」
5. 选择JSON格式的书源文件

### 书源格式
书源文件为JSON格式，示例：
```json
{
  "bookSourceName": "书源名称",
  "bookSourceUrl": "https://example.com",
  "searchUrl": "搜索地址",
  "ruleBookInfo": {},
  "ruleToc": {},
  "ruleContent": {}
}
```

## 项目结构
```
legado/
├── app/                    # 主应用模块
│   ├── src/main/
│   │   ├── assets/        # 资源文件
│   │   │   └── defaultData/bookSources.json  # 内置书源
│   │   ├── java/          # 源代码
│   │   └── res/           # 资源文件
│   └── build.gradle       # 构建配置
├── modules/               # 功能模块
│   ├── book/              # 书籍解析模块
│   ├── rhino/             # JavaScript引擎
│   └── web/               # Web服务模块
└── build.gradle           # 根构建配置
```

## 注意事项
1. 书源有效性可能随时间变化
2. 部分书源可能需要网络连接
3. 建议定期更新书源
4. 请尊重版权，支持正版

## 技术支持
- 基于Legado开源项目
- 项目地址：https://github.com/gedoor/legado
