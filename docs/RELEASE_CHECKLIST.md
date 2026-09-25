# v0.1.0 发布检查清单

## A. 代码与依赖

- [ ] `npm ci` 成功
- [ ] `npm run check` 成功
- [ ] `npm test` 全部通过
- [ ] `npm run smoke:epub` 成功
- [ ] `npm run test:live` 成功
- [ ] `package.json` 与 `package-lock.json` 版本均为 `0.1.0`
- [ ] `npm ls --depth=0` 无依赖错误
- [ ] Git 工作区已自审

## B. 启动与 UI

- [ ] 双击 `start.bat` 可启动
- [ ] 首次运行可安装依赖
- [ ] `http://127.0.0.1:3210` 可访问
- [ ] 输入示例 URL 后进入章节步骤
- [ ] 章节复选框、搜索、全选和清空正常
- [ ] “继续导出”可进入导出步骤
- [ ] 任务进度、取消、警告、历史和下载按钮正常
- [ ] 浏览器控制台无 JavaScript 错误
- [ ] 窄屏布局可用

## C. 源站与任务

- [ ] 公开书籍元数据解析成功
- [ ] 公开目录解析成功
- [ ] 普通正文章节导出成功
- [ ] 插图章节导出成功
- [ ] 图片写入 EPUB，不依赖远程图片链接
- [ ] 下载接口返回 `application/epub+zip`
- [ ] 下载文件名支持中文
- [ ] 验证页、403、429 会明确失败且不尝试绕过

## D. EPUB

- [ ] 第一个 ZIP 条目是未压缩的 `mimetype`
- [ ] `mimetype` 内容为 `application/epub+zip`
- [ ] `META-INF/container.xml` 存在且 XML 有效
- [ ] `EPUB/package.opf` 存在且 XML 有效
- [ ] `EPUB/nav.xhtml` 存在且 XML 有效
- [ ] `EPUB/toc.ncx` 存在且 XML 有效
- [ ] 标题页、章节 XHTML 和 CSS 存在
- [ ] manifest 和 spine 引用完整
- [ ] 所有成功下载的图片存在
- [ ] 封面标记为 `cover-image`
- [ ] 封面路径为 `../images/...`
- [ ] 章节不引用远程图片
- [ ] 不残留 `.tmp` 文件
- [ ] 至少在两个 EPUB 阅读器中人工打开成功

## E. 发布包

- [ ] `README.md`、用户 SOP、研发 SOP 和本清单齐全
- [ ] `CHANGELOG.md` 已更新
- [ ] `npm run release:package` 成功
- [ ] ZIP 不含 `node_modules/`、`data/jobs/`、`output/*.epub`、`work/`
- [ ] SHA-256 校验文件已生成
- [ ] 发布说明写明 Node.js 20+、已知限制和回滚方式
- [ ] 创建 `v0.1.0` 标签
