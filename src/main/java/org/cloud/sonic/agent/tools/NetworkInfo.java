package org.cloud.sonic.agent.tools;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.NetworkInterface;

@Slf4j
public class NetworkInfo {

    public static String getHostIP() {
        try {
            InetAddress candidateAddress = null;

            var networkInterfaces = NetworkInterface.networkInterfaces().toList();
            for (var networkInterface : networkInterfaces) {
                // 排除docker0接口
                if (networkInterface.getName().equals("docker0")) {
                    continue;
                }
                // 遍历每个网卡接口下的 IP 地址
                var inetAddresses = networkInterface.inetAddresses().toList();
                for (var inetAddress : inetAddresses) {
                    // 排除回环地址
                    if (!inetAddress.isLoopbackAddress()) {
                        if (inetAddress.isSiteLocalAddress()) {
                            // 找到 site-local 地址
                            return inetAddress.getHostAddress();
                        }
                        // 记录非 site-local 地址作为候选
                        if (candidateAddress == null) {
                            candidateAddress = inetAddress;
                        }
                    }
                }
            }

            // 如果没有找到合适的地址，则使用默认地址方案
            return candidateAddress != null ? candidateAddress.getHostAddress() : InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.error("getLocalHostExactAddress exception", e);
        }
        return "";
    }

    public static void main(String[] args) {
        System.out.println("----------------下面才是正确的获取方式----------------");
        var ip = getHostIP();
        System.out.println(ip);
    }

}
