package com.whmdg.mczj.tools.ui.packagemanager

import java.util.zip.ZipFile

/**
 * APK 加固特征检测。
 *
 * 特征表照搬自开源项目 ApkCheckPack（https://github.com/moyuwa/ApkCheckPack，MIT License），
 * 覆盖 40+ 加固厂商，仅使用 Zip 层特征（Sopath 精确路径 / Soname 包含 / Other 包含 / Soregex 正则），
 * 不读取 DEX 内容（原项目的 Jclass 特征已省略）。
 *
 * 判定规则：
 *  - 未命中任何厂商 -> 「未加固」
 *  - 恰好命中 1 个厂商 -> 该厂商名
 *  - 命中 2 个及以上厂商 -> 「未知加固」
 */
internal object AppHardeningDetector {

    private class Rule(
        val name: String,
        val sopath: List<String> = emptyList(),
        val soname: List<String> = emptyList(),
        val other: List<String> = emptyList(),
        soregexPatterns: List<String> = emptyList()
    ) {
        val soregex: List<Regex> = soregexPatterns.map { Regex(it) }
    }

    private val rules: List<Rule> = listOf(
        Rule(
            name = """appguard.us加固""",
            sopath = listOf("""lib/arm64-v8a/libAppGuard.so""", """lib/arm64-v8a/libdiresu.so"""),
            soname = listOf("""libloader.so""", """libAppGuard.so""", """libAppGuard-x86.so""", """libdiresu.so"""),
        ),
        Rule(
            name = """深盾安全加固（Virbox Protector）""",
            soname = listOf("""ibvirbox32.so""", """libvirbox64.so"""),
            soregexPatterns = listOf("""libvirbox..\.so"""),
        ),
        Rule(
            name = """网秦加固（国信灵通）""",
            soname = listOf("""libnqshield.so"""),
        ),
        Rule(
            name = """腾讯乐固（VMP）""",
            sopath = listOf("""lib/arm64-v8a/libxgVipSecurity.so""", """lib/armeabi-v7a/libxgVipSecurity.so"""),
            soname = listOf("""libxgVipSecurity.so"""),
        ),
        Rule(
            name = """通付盾""",
            soname = listOf("""libNSaferOnly.so""", """libegis.so""", """libgeiri.so""", """libgeiri-x86.so"""),
        ),
        Rule(
            name = """阿里加固""",
            sopath = listOf("""assets/armeabi/libzuma.so""", """assets/libpreverify1.so""", """assets/libzuma.so""", """assets/libzumadata.so"""),
            soname = listOf("""libzuma.so""", """libpreverify1.so""", """libmobisec.so""", """libsgmain.so""", """libsgsecuritybody.so"""),
            other = listOf("""aliprotect.dat"""),
        ),
        Rule(
            name = """顶像科技""",
            sopath = listOf("""lib/armeabi/libx3g.so""", """lib/arm64-v8a/libsys_misc.so"""),
            soname = listOf("""libx3g.so""", """libjni.so""", """libsys_misc.so"""),
        ),
        Rule(
            name = """CFCA加固""",
            soname = listOf("""libbasec.so""", """libbasec_x86.so""", """libsecenh.so""", """libsecenh_a64.so""", """libsecenh_x86.so"""),
            other = listOf("""my_classes.jar"""),
        ),
        Rule(
            name = """爱加密""",
            sopath = listOf("""assets/ijm_lib/armeabi/libexec.so""", """assets/ijm_lib/X86/libexec.so""", """lib/armeabi/libexecmain.so"""),
            soname = listOf("""libexecmain.so""", """libexec.so"""),
            other = listOf("""assets/af.bin""", """assets/signed.bin""", """ijiami.dat"""),
        ),
        Rule(
            name = """腾讯云加固""",
            sopath = listOf("""assets/libshellx-super.2021.so""", """lib/armeabi/libshell-super.2019.so""", """lib/armeabi/libshell-super.2020.so""", """lib/armeabi/libshell-super.2021.so"""),
            soname = listOf("""libshell-super.2019.so""", """libshellx-super.2021.so"""),
            other = listOf("""tencent_sub"""),
            soregexPatterns = listOf("""libshellx\-super\.\d+\.so""", """libshell\-super\.\d+\.so"""),
        ),
        Rule(
            name = """阿里云加固""",
            soname = listOf("""libdemolish.so""", """libdemolishdata.so"""),
        ),
        Rule(
            name = """阿里聚安全""",
            sopath = listOf("""assets/armeabi/libfakejni.so""", """assets/libpreverify1.so""", """assets/libzuma.so""", """assets/libzumadata.so"""),
            soname = listOf("""libdemolish.so""", """libdemolishdata.so""", """libfakejni.so""", """libmobisec.so""", """libpreverify1.so""", """libsgmain.so""", """libsgsecuritybody.so""", """libzumadata.so"""),
            other = listOf("""aliprotect.dat"""),
        ),
        Rule(
            name = """360加固""",
            sopath = listOf("""assets/libjiagu.so"""),
            soname = listOf("""libjiagu.so""", """libjgdtc.so""", """libjgdtc_a64.so""", """libjgdtc_art.so""", """libjgdtc_x64.so""", """libjgdtc_x86.so""", """libjiagu.so""", """libjiagu_a64.so""", """libjiagu_art.so""", """libjiagu_ls.so""", """libjiagu_x64.so""", """libjiagu_x86.so""", """libprotectClass.so""", """libSafeManageService.so"""),
            other = listOf("""assets/.appkey"""),
            soregexPatterns = listOf("""libjiagu\_...\.so""", """libjgdtc\_...\.so"""),
        ),
        Rule(
            name = """APKProtect""",
            soname = listOf("""libAPKProtect.so"""),
        ),
        Rule(
            name = """OPPO安全检测SDK""",
            sopath = listOf("""jni/arm64-v8a/libomesStdSco.so""", """jni/armeabi-v7a/libomesStdSco.so""", """jni/x86/libomesStdSco.so""", """jni/x86_64/libomesStdSco.so"""),
            soname = listOf("""libomesStdSco.so"""),
        ),
        Rule(
            name = """梆梆安全（定制版）""",
            sopath = listOf("""lib/armeabi/DexHelper.so"""),
            soname = listOf("""DexHelper.so"""),
            other = listOf("""assets/classes.jar"""),
        ),
        Rule(
            name = """爱加密5代壳""",
            sopath = listOf("""assets/libijmDataEncryption.so"""),
            soname = listOf("""libijmDataEncryption.so"""),
            other = listOf("""assets/IJMDal.Data"""),
        ),
        Rule(
            name = """腾讯加固""",
            soname = listOf("""libshell.so"""),
        ),
        Rule(
            name = """google(play)加固""",
            sopath = listOf("""lib/arm64-v8a/libpairipcore.so""", """lib/armeabi-v7a/libpairipcore.so""", """lib/x86_64/libpairipcore.so""", """lib/x86/libpairipcore.so"""),
            soname = listOf("""libpairipcore.so"""),
        ),
        Rule(
            name = """LIAPP加固""",
            other = listOf("""assets/LIAPP.ini""", """assets/pkgInfo.txt"""),
            soregexPatterns = listOf("""com\..+UnityDataAssetPack.+\.apk$""", """com\..+AddressablesAssetPack.+\.apk$"""),
        ),
        Rule(
            name = """未知厂商""",
            soname = listOf("""libapk-protect.so"""),
        ),
        Rule(
            name = """apktoolplus""",
            sopath = listOf("""lib/armeabi/libapktoolplus_jiagu.so"""),
            soname = listOf("""libapktoolplus_jiagu.so"""),
            other = listOf("""assets/jiagu_data.bin""", """assets/sign.bin"""),
        ),
        Rule(
            name = """能信安科技""",
            soname = listOf("""libzprotect.so"""),
        ),
        Rule(
            name = """娜迦加固（新版2022）""",
            sopath = listOf("""lib/armeabi/libxloader.so""", """lib/armeabi-v7a/libxloader.so""", """lib/arm64-v8a/libxloader.so"""),
            soname = listOf("""libxloader.so"""),
            other = listOf("""assets/maindata/fake_classes.dex"""),
        ),
        Rule(
            name = """瑞星加固""",
            soname = listOf("""librsprotect.so"""),
        ),
        Rule(
            name = """网易易盾""",
            soname = listOf("""libnesec.so"""),
        ),
        Rule(
            name = """腾讯御安全""",
            sopath = listOf("""assets/libtosprotection.armeabi-v7a.so""", """assets/libtosprotection.armeabi.so""", """assets/libtosprotection.x86.so""", """lib/armeabi/libtest.so""", """lib/armeabi/libTmsdk-xxx-mfr.so"""),
            soname = listOf("""libtosprotection.armeabi-v7a.so""", """libtosprotection.armeabi.so""", """libtosprotection.x86.so"""),
            other = listOf("""assets/tosversion"""),
            soregexPatterns = listOf("""libtosprotection\..+\.so"""),
        ),
        Rule(
            name = """中国移动加固""",
            sopath = listOf("""lib/armeabi/libcmvmp.so""", """lib/armeabi/libmogosec_dex.so""", """lib/armeabi/libmogosec_sodecrypt.so""", """lib/armeabi/libmogosecurity.so"""),
            soname = listOf("""libcmvmp.so""", """libmogosec_dex.so""", """libmogosec_sodecrypt.so""", """libmogosecurity.so""", """ibmogosecurity.so"""),
            other = listOf("""assets/mogosec_classes""", """assets/mogosec_data""", """assets/mogosec_dexinfo""", """assets/mogosec_march"""),
        ),
        Rule(
            name = """爱加密企业版""",
            other = listOf("""assets/ijiami.ajm"""),
        ),
        Rule(
            name = """百度加固""",
            sopath = listOf("""lib/armeabi/libbaiduprotect.so"""),
            soname = listOf("""libbaiduprotect.so""", """libbaiduprotect_art.so""", """libbaiduprotect_x86.so"""),
            other = listOf("""assets/baiduprotect.jar""", """assets/baiduprotect1.jar"""),
        ),
        Rule(
            name = """腾讯乐固（旧版）""",
            sopath = listOf("""lib/armeabi/libshella-xxxx.so""", """lib/armeabi/libshellx-xxxx.so"""),
            soname = listOf("""liblegudb.so""", """libshel1x.so""", """libshell.so""", """libshella-2.10.2.3.so""", """libshella-2.9.0.2.so""", """libshella-4.1.0.15.so""", """libshella-4.1.0.19.so""", """libshella.so""", """libshellx.so""", """libtup.so"""),
            other = listOf("""lib/armeabi/mix.dex""", """lib/armeabi/mixz.dex""", """tencent_stub"""),
            soregexPatterns = listOf("""libshella\-\d+\.\d+\.\d+\.\d+\.so""", """libWSSec(V?)\.so"""),
        ),
        Rule(
            name = """腾讯手游加固""",
            soname = listOf("""libtprt.so"""),
        ),
        Rule(
            name = """腾讯云移动应用安全（腾讯御安全）""",
            soname = listOf("""libBugly-yaq.so""", """libshell-super.2019.so""", """libshellx-super.2019.so""", """libzBugly-yaq.so"""),
            other = listOf("""000000011111.dex""", """000000111111.dex""", """000001111111""", """00000o11111.dex""", """o0ooo000oo0o.dat""", """t86""", """tosprotection""", """tosversion"""),
        ),
        Rule(
            name = """蛮犀加固""",
            sopath = listOf("""assets/mxsafe/arm64-v8a/libdSafeShell.so""", """assets/mxsafe/x86_64/libdSafeShell.so""", """assets/mx/lib/arm64-v8a/libmxacc.so""", """assets/mx/lib/x86_64/libmxacc.so"""),
            soname = listOf("""libdSafeShell.so""", """libmxacc.so""", """libmanxi.so"""),
            other = listOf("""assets/mxsafe.config""", """assets/mxsafe.data""", """assets/mxsafe.jar"""),
        ),
        Rule(
            name = """几维安全""",
            sopath = listOf("""lib/armeabi/kdpdata.so""", """lib/armeabi/libkdp.so""", """lib/armeabi/libkwscmm.so""", """lib/arm64-v8a/libkadp.so""", """lib/arm64-v8a/libkiwi_dumper.so""", """lib/arm64-v8a/libkiwicrash.so""", """lib/arm64-v8a/libKwProtectSDK.so""", """lib/arm64-v8a/libkwdataenc.so"""),
            soname = listOf("""kdpdata.so""", """libkdp.so""", """libkwscmm.so""", """libkwscr.so""", """libkwslinker.so""", """libkadp.so""", """libkiwi_dumper.so""", """libkiwicrash.so""", """libKwProtectSDK.so""", """libkwdataenc.so"""),
            other = listOf("""assets/dex.dat"""),
        ),
        Rule(
            name = """启明星辰""",
            soname = listOf("""libvenSec.so""", """libvenustech.so"""),
        ),
        Rule(
            name = """娜迦加固""",
            soname = listOf("""libchaosvmp.so""", """libddog.so""", """libfdog.so""", """libhdog.so"""),
            soregexPatterns = listOf("""lib.dog\.so"""),
        ),
        Rule(
            name = """梆梆安全（免费版）""",
            sopath = listOf("""lib/armeabi/libSecShell-x86.so""", """lib/armeabi/libSecShell.so"""),
            soname = listOf("""libSecShell_art.so""", """libSecShell.so""", """libSecShel1.so""", """libsecexe.so""", """libsecmain.so"""),
            other = listOf("""assets/secData0.jar"""),
        ),
        Rule(
            name = """珊瑚灵御""",
            sopath = listOf("""assets/libreincp.so""", """assets/libreincp_x86.so"""),
            soname = listOf("""libreincp.so""", """libreincp_x86.so"""),
            soregexPatterns = listOf("""libreincp\_...\.so"""),
        ),
        Rule(
            name = """腾讯Bugly""",
            sopath = listOf("""lib/arm64-v8a/libBugly.so"""),
            soname = listOf("""libBugly.so"""),
        ),
        Rule(
            name = """XAPK内嵌""",
            soregexPatterns = listOf("""config\..+\.apk$""", """com\..+\..+\.apk$"""),
        ),
        Rule(
            name = """娜迦加固（企业版）""",
            soname = listOf("""libedog.so"""),
        ),
        Rule(
            name = """娜迦加固（开发者试用版-VMP）""",
            soname = listOf("""libvdog-x86.so""", """libvdog.so"""),
            soregexPatterns = listOf("""libvdog\-...\.so"""),
        ),
        Rule(
            name = """爱加密3代壳""",
            soname = listOf("""libexecv3.so"""),
            other = listOf("""assets/ijiami3.ajm"""),
        ),
        Rule(
            name = """腾讯云移动应用安全""",
            other = listOf("""0000000lllll.dex""", """00000olllll.dex""", """000O00ll111l.dex""", """00O000ll111l.dex""", """0OO00l111l1l""", """o0oooOO0ooOo.dat"""),
        ),
        Rule(
            name = """G-Presto加固""",
            sopath = listOf("""lib/arm64-v8a/libATG_L.so"""),
            soname = listOf("""libATG_D.so""", """libATG_H.so""", """libATG_L.so"""),
            other = listOf("""assets/ATG_E.sec""", """assets/ATG_E_x64.sec""", """assets/ATG_E_x86.sec""", """assets/ATG_E_x86_64.sec"""),
        ),
        Rule(
            name = """DexProtect加固""",
            other = listOf("""assets/classes.dex.dat""", """dp.arm-v7.so.dat""", """dp.arm.so.dat"""),
        ),
        Rule(
            name = """OPPO应用加固""",
            soname = listOf("""OPPOProtect.so""", """OPPOProtect2019.so"""),
            soregexPatterns = listOf("""OPPOProtect\d\d\d\d\.so"""),
        ),
        Rule(
            name = """UU安全加固""",
            sopath = listOf("""assets/libuusafe.jar.so""", """assets/libuusafe.so""", """lib/armeabi/libuusafeempty.so"""),
            soname = listOf("""libuusafe.jar.so""", """libuusafe.so""", """libuusafeempty.so"""),
        ),
        Rule(
            name = """梆梆安全（企业版）""",
            soname = listOf("""libDexHelper-x86.so""", """libDexHelper.so"""),
            soregexPatterns = listOf("""libDexHelper\-...\.so"""),
        ),
        Rule(
            name = """海云安加固""",
            sopath = listOf("""lib/armeabi/libitsec.so"""),
            soname = listOf("""libitsec.so"""),
            other = listOf("""assets/itse"""),
        ),
        Rule(
            name = """盛大加固""",
            soname = listOf("""libapssec.so"""),
        ),
    )

    /**
     * 检测 APK 的加固厂商。
     *
     * @return 厂商名 / 「未知加固」 / 「未加固」 / 「检测失败」
     */
    fun detect(apkPath: String): String {
        return try {
            val entries = ZipFile(apkPath).use { zip ->
                zip.entries().asSequence().map { it.name }.toList()
            }
            val matched = linkedSetOf<String>()
            for (rule in rules) {
                if (matches(rule, entries)) {
                    matched.add(rule.name)
                }
            }
            when {
                matched.isEmpty() -> "未加固"
                matched.size == 1 -> matched.first()
                else -> "未知加固"
            }
        } catch (_: Exception) {
            "检测失败"
        }
    }

    private fun matches(rule: Rule, entries: List<String>): Boolean {
        for (path in rule.sopath) {
            if (entries.contains(path)) return true
        }
        for (name in rule.soname) {
            if (entries.any { it.contains(name) }) return true
        }
        for (name in rule.other) {
            if (entries.any { it.contains(name) }) return true
        }
        for (regex in rule.soregex) {
            if (entries.any { regex.containsMatchIn(it) }) return true
        }
        return false
    }
}
