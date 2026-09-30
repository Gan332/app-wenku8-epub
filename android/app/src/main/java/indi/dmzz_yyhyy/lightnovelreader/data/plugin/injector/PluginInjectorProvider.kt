package indi.dmzz_yyhyy.lightnovelreader.data.plugin.injector

/**
 * LNR 引入适配：LNR 原 [PluginInjector] 依赖整套 data 层
 * （Hilt / Room / 书架 / 文本处理 / WorkManager 预取），本工程一概不引入。
 *
 * [ContentComponentRepository] 对注入器的全部需求是把
 * `ParagraphComponentRender` / `ImageComponentRender` 实例化出来 ——
 * 两者都是 object 或无参类，因此这里只实现 LNR 原逻辑的前两个分支
 * （`INSTANCE` 字段 → 无参构造），不带 injectMap、不带插件系统。
 */
class PluginInjector {

    @Suppress("UNCHECKED_CAST")
    fun <T> provide(clazz: Class<*>): T? {
        try {
            return clazz.getDeclaredField("INSTANCE").get(null) as T
        } catch (_: NoSuchFieldException) {
        } catch (_: ClassCastException) {
        }
        return try {
            clazz.getDeclaredConstructor().newInstance() as T
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * 保持与 LNR 相同的持有形态（`value` + setter），但 **value 初值即为内置注入器** ——
 * LNR 原版是 `null` 且调用处 `value!!.provide(...)` 会崩；本工程没有插件系统去 set，
 * 初值非空保证 [ContentComponentRepository.initRegister] 可以直接工作。
 */
class PluginInjectorProvider {
    var value: PluginInjector? = PluginInjector()
        private set

    fun setPluginInjector(injector: PluginInjector) {
        value = injector
    }
}
