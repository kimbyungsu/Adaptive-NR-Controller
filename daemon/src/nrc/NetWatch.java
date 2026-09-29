package nrc;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Constructor;

/**
 * 기본 인터넷 경로 감시: 지금 인터넷이 Wi-Fi로 나가는지(셀룰러가 아닌지).
 * Wi-Fi로 인터넷을 쓰는 동안에는 5G(셀룰러) 품질을 판정할 대상이 없으므로 컨트롤러가 쉰다(DESIGN §5.11).
 * app_process에는 앱 컨텍스트가 없어, shell 패키지 컨텍스트로 ConnectivityManager를 직접 만든다(레퍼런스 기기에서 동작 확인).
 */
final class NetWatch {
    interface Sink {
        /** wifi = 기본 경로가 Wi-Fi(셀룰러 아님). 알림 스레드에서 불리므로 받는 쪽이 작업 스레드로 넘긴다. */
        void onDefaultNetwork(boolean wifi, String desc);
    }

    // GC로 풀리지 않게 잡아 둔다
    private static ConnectivityManager.NetworkCallback callback;

    private NetWatch() {
    }

    /** 등록 성공이면 true. 실패하면 호출한 쪽이 제어를 멈춘다(Nrd: 관찰만). */
    static boolean start(Context ctx, Sink sink) {
        try {
            IBinder b = Phone.service("connectivity");
            Class<?> icm = Class.forName("android.net.IConnectivityManager");
            Object svc = Class.forName("android.net.IConnectivityManager$Stub").getMethod("asInterface", IBinder.class)
                    .invoke(null, b);
            Constructor<ConnectivityManager> c = ConnectivityManager.class.getDeclaredConstructor(Context.class, icm);
            c.setAccessible(true);
            ConnectivityManager cm = c.newInstance(ctx, svc);
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network net, NetworkCapabilities nc) {
                    boolean wifi = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            && !nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                    sink.onDefaultNetwork(wifi, wifi ? "wifi" : (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            ? "cellular" : "other"));
                }

                @Override
                public void onLost(Network net) {
                    sink.onDefaultNetwork(false, "none");
                }
            };
            cm.registerDefaultNetworkCallback(callback, new Handler(Looper.getMainLooper()));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
