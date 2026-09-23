掌中灵 — Release 签名密钥
=========================

本目录存放 Android release 包的签名密钥（keystore）。

文件
----
  zhangzhongling.jks     签名密钥，别名 zhangzhongling，RSA 2048 位，有效期 10000 天
                         ⚠️ 该文件被 .gitignore 排除，不会提交到版本库

签名口令保存在项目根的 android/local.properties 中（同样不进版本库）：

  RELEASE_STORE_FILE      = keystore/zhangzhongling.jks
  RELEASE_STORE_PASSWORD  = （32 位随机口令）
  RELEASE_KEY_ALIAS       = zhangzhongling
  RELEASE_KEY_PASSWORD    = （同上）

查看当前配置（不回显完整口令）：

  node scripts/gen-keystore.cjs --show


⚠️ 必须备份
----------
Android 用签名证书判断"新版本是不是同一个应用"。

**keystore 或口令一旦丢失，后续版本将无法覆盖安装**，
只能先把旧版本卸载（会丢失设备配对与全部管控设置），再装新包。

请立刻把下面两个文件一起备份到云盘或密码管理器：

  1. android/keystore/zhangzhongling.jks
  2. android/local.properties

两者缺一不可 —— 只有 keystore 没有口令，同样打不开。


重新生成
--------
  node scripts/gen-keystore.cjs            # 已存在时会跳过（保护现有密钥）
  node scripts/gen-keystore.cjs --force    # 强制重建（会放弃覆盖升级能力）

说明：自签名证书即可满足侧载安装，**不需要付费、不需要上架应用商店**。
本密钥仅用于本项目自己的 APK 签名，与任何厂商或 CA 无关。
