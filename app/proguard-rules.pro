# Add project specific ProGuard rules here.
-keep class com.google.mlkit.** { *; }

# H10: kotlinx.serialization 生成的 serializer 不能被混淆/移除，否则 release 运行时崩溃
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# H10: 枚举常量名被 valueOf 和 system prompt 引用，不能被 R8 重命名
-keepnames enum com.legalreview.analysis.RiskCategory { *; }
-keepnames enum com.legalreview.analysis.Severity { *; }
-keepnames enum com.legalreview.analysis.FindingSource { *; }

# 保留可序列化数据类（ResultSink encode/decode）
-keep class com.legalreview.analysis.RiskFinding { *; }
-keep class com.legalreview.analysis.AnalysisResult { *; }
