package com.mhmdjalal.imagepicker

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

@GlideModule
class MyGlideModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val client = OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(60, TimeUnit.SECONDS)
                .build()
        val factory = OkHttpUrlLoader.Factory(client)
        glide.registry.replace(GlideUrl::class.java, InputStream::class.java, factory)
    }

    /*override fun applyOptions(context: Context, builder: GlideBuilder) {
        val circularProgress = Utils
                .circularProgress(context)
        val requestOption = RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(circularProgress)
        builder.setDefaultRequestOptions(requestOption)
    }

    override fun isManifestParsingEnabled(): Boolean {
        return false
    }*/
}
