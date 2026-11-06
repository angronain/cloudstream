package com.lagradost.cloudstream4.desktop.data

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/** The DNS over HTTPS services of the Android app, by the address and the IPs to reach it without DNS */
enum class DnsProvider(val title: String, val url: String?, val ips: List<String>) {
    System("System", null, emptyList()),
    Google("Google", "https://dns.google/dns-query", listOf("8.8.4.4", "8.8.8.8")),
    Cloudflare("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")),
    AdGuard("AdGuard", "https://dns.adguard.com/dns-query", listOf("94.140.14.140", "94.140.14.141")),
    DnsWatch("DNS.WATCH", "https://resolver2.dns.watch/dns-query", listOf("84.200.69.80", "84.200.70.40")),
    Quad9("Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
    DnsSb("DNS.SB", "https://doh.dns.sb/dns-query", listOf("185.222.222.222", "45.11.45.11")),
    CanadianShield("Canadian Shield", "https://private.canadianshield.cira.ca/dns-query", listOf("149.112.121.10", "149.112.122.10")),
}

/**
 * Many internet providers block streaming sites by answering their DNS lookups with nothing or with this
 * computer's own address, so every source of an extension fails. The app's requests can look names up through
 * a DNS over HTTPS service instead, picked in Settings and used from the next request on.
 */
object AppDns : Dns {
    private var resolvers = mapOf<DnsProvider, Dns>()

    val isOn: Boolean get() = AppData.settings.value.dns != DnsProvider.System

    /** Extensions use these two clients, the stream proxy and downloads build on the first */
    fun install() {
        app.baseClient = app.baseClient.newBuilder().dns(this).build()
        insecureApp.baseClient = insecureApp.baseClient.newBuilder().dns(this).build()
    }

    override fun lookup(hostname: String): List<InetAddress> = resolver(AppData.settings.value.dns).lookup(hostname)

    @Synchronized
    private fun resolver(provider: DnsProvider): Dns = resolvers[provider] ?: run {
        val url = provider.url ?: return Dns.SYSTEM
        DnsOverHttps.Builder()
            // Its own client, a lookup going through this resolver again would never end
            .client(OkHttpClient())
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(provider.ips.map(InetAddress::getByName))
            .build()
            .also { resolvers = resolvers + (provider to it) }
    }
}
