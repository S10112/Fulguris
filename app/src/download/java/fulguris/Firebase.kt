package fulguris

import android.content.Context

object Firebase {
    /**
     * 彻底剥离原版的 Firebase 统计与崩溃收集。
     * 此处保留一个空的 setup 方法，以防止上层应用初始化时找不到方法而报错。
     */
    fun setup(context: Context) {
        // 留空，不再向 Google 发送任何遥测数据
    }
}
