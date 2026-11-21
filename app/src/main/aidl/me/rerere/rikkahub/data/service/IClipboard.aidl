// 【剪贴板 · 2026-11-21】
// Android 10+ 之后，普通 App 在后台读不到剪贴板（官方文档：非默认输入法、且没有输入焦点 → 返回 null）。
// 但可以用 Shizuku 借 shell 身份直接问系统 clipboard 服务要 —— 那条路不受焦点限制。
//
// ⚠️ AIDL 的 transaction code 是按「方法声明顺序」自动分配的，所以包名可以跟系统不一样，
// 只要方法签名跟设备上的 IClipboard 一致就行。Android 13~15 的签名是下面这个。
package me.rerere.rikkahub.data.service;

import android.content.ClipData;

interface IClipboard {
    ClipData getPrimaryClip(String pkg, String attributionTag, int userId, int deviceId, boolean sendAccessNotify);
}
