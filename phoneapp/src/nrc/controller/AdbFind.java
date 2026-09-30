package nrc.controller;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Collections;

/**
 * 무선 디버깅이 알리는 이 폰의 서비스 포트를 찾는다(mDNS, NsdManager).
 * - 페어링 코드 창이 열려 있는 동안: "_adb-tls-pairing._tcp"
 * - 무선 디버깅이 켜져 있는 동안: "_adb-tls-connect._tcp"
 * 같은 Wi-Fi의 다른 폰·PC도 같은 이름을 알리므로, 풀어 낸 주소가 이 폰의 주소일 때만 쓴다.
 * 찾은 포트는 계속 갱신한다(창을 닫았다 다시 열면 포트가 바뀐다). 사라지면 -1.
 */
final class AdbFind {
    static final String PAIRING = "_adb-tls-pairing._tcp";
    static final String CONNECT = "_adb-tls-connect._tcp";

    private final NsdManager nsd;
    private final String type;
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private volatile int port = -1;
    private String foundName;
    private NsdManager.DiscoveryListener listener;

    AdbFind(Context c, String type) {
        this.nsd = c.getSystemService(NsdManager.class);
        this.type = type;
    }

    int port() {
        return port;
    }

    synchronized void start() {
        if (listener != null) return;
        listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String t, int err) {
                synchronized (AdbFind.this) {
                    listener = null;
                }
            }

            @Override
            public void onStopDiscoveryFailed(String t, int err) {
            }

            @Override
            public void onDiscoveryStarted(String t) {
            }

            @Override
            public void onDiscoveryStopped(String t) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                enqueue(info);
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                synchronized (AdbFind.this) {
                    if (info.getServiceName() != null && info.getServiceName().equals(foundName)) {
                        port = -1;
                        foundName = null;
                    }
                }
            }
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            listener = null;
        }
    }

    synchronized void stop() {
        if (listener == null) return;
        try {
            nsd.stopServiceDiscovery(listener);
        } catch (RuntimeException ignored) {
            // 이미 멈췄으면 무시
        }
        listener = null;
    }

    /** 이미 찾았으면 곧바로, 아니면 timeoutMs까지 기다린다. 못 찾으면 -1. */
    int await(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (port < 0 && System.currentTimeMillis() < end) Thread.sleep(200);
        return port;
    }

    // 한 번에 하나씩 풀어 낸다(동시에 여럿 풀면 거절되는 판이 있다)
    private synchronized void enqueue(NsdServiceInfo info) {
        toResolve.add(info);
        next();
    }

    private synchronized void next() {
        if (resolving || toResolve.isEmpty()) return;
        NsdServiceInfo info = toResolve.poll();
        resolving = true;
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() { // 실행자를 받는 판은 안드로이드 13부터라 쓰지 않는다
                @Override
                public void onResolveFailed(NsdServiceInfo i, int err) {
                    done();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo i) {
                    if (isThisPhone(i.getHost())) {
                        synchronized (AdbFind.this) {
                            port = i.getPort();
                            foundName = i.getServiceName();
                        }
                    }
                    done();
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }

    private synchronized void done() {
        resolving = false;
        next();
    }

    static boolean isThisPhone(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress()) return true;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress mine : Collections.list(ni.getInetAddresses())) {
                    if (mine.equals(a)) return true;
                }
            }
        } catch (Exception ignored) {
            // 목록을 못 읽으면 이 폰이 아닌 것으로 본다
        }
        return false;
    }
}
