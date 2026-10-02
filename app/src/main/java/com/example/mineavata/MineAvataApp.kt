package com.example.mineavata

import android.app.Application
import com.example.mineavata.pet.ImportManager

class MineAvataApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 清理上次进程导入中断（崩溃/被杀/断电）遗留的孤儿临时文件，每进程一次。
        // 此刻必然没有导入在跑（job 随旧进程死掉了），整目录递归删安全。
        ImportManager.sweepOrphans(this)
    }
}
