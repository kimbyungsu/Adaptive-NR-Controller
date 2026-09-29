package nrc;

import android.os.IBinder;

/**
 * 상단바 작동 표시(DESIGN §5.8). shell의 STATUS_BAR 권한으로 빈 자리에 아이콘을 올린다.
 * 자리 규칙 🔒: 비어 있을 때만 올리고, 지울 때는 그 자리 아이콘이 우리 것(패키지·아이콘 id)일 때만 지운다.
 * 시스템이 자리를 덮어쓰면 양보하고 다시 올리지 않는다(lost → 제어 불가 조건).
 */
final class Indicator {
    /** 동반 앱이 없을 때 쓰는 프레임워크 공개 아이콘(원, 상단바에서는 한 가지 색으로 크게 칠해짐). */
    static final String FALLBACK_PKG = "android";
    static final int FALLBACK_ICON = android.R.drawable.presence_online;
    static final String DESC = "5G 컨트롤러 작동 중";

    private final Journal log;
    private final String slot;
    private final String pkg;
    private final int icon;
    private boolean shown;
    private boolean gaveUp;

    /** pkg·icon: 동반 앱의 작은 점 아이콘(nrctl이 설치 여부를 보고 넘긴다). null이면 프레임워크 원. */
    Indicator(Journal log, String slot, String pkg, int icon) {
        this.log = log;
        this.slot = slot;
        this.pkg = pkg != null ? pkg : FALLBACK_PKG;
        this.icon = pkg != null ? icon : FALLBACK_ICON;
    }

    String slot() {
        return slot;
    }

    private String ours() {
        return pkg + "/" + icon;
    }

    /** 자리 주인("패키지/아이콘id"), 비어 있으면 "", 조회 실패 null. `dumpsys statusbar`의 mIcons 목록을 읽는다. */
    String owner() {
        String out = Exec.run("/system/bin/dumpsys", "statusbar");
        if (out.startsWith("[exec error]") || !out.contains("mIcons")) return null;
        String prefix = slot + " -> StatusBarIcon(";
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (!s.startsWith(prefix)) continue;
            String pkg = field(s, "pkg=");
            String id = field(s, "id=");
            if (pkg == null || id == null) return "?";
            try {
                return pkg + "/" + (int) Long.parseLong(id.replace("0x", ""), 16);
            } catch (NumberFormatException e) {
                return "?";
            }
        }
        return "";
    }

    private static String field(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return null;
        int j = i + key.length();
        int k = j;
        while (k < s.length() && s.charAt(k) != ' ' && s.charAt(k) != ')') k++;
        return s.substring(j, k);
    }

    /** 올릴 수 있는 자리인지(비어 있거나 이미 우리 것). */
    boolean usable() {
        String o = owner();
        return o != null && (o.isEmpty() || o.equals(ours()));
    }

    /** 표시. 성공하면 true. 자리가 남의 것이거나 올린 뒤 확인이 안 되면 false. */
    boolean show() {
        if (gaveUp) return false;
        String o = owner();
        if (o == null) return false;
        if (o.equals(ours())) {
            shown = true;
            return true;
        }
        if (shown) {
            // 표시 중이던 자리를 잃었다(시스템이 지웠거나 덮어씀): 새 빈 자리로 보지 않고 양보한다 → 제어 불가
            shown = false;
            gaveUp = true;
            log.write("indicator", "result", "lost", "owner", o);
            return false;
        }
        if (!o.isEmpty()) {
            log.write("indicator", "result", "slot_busy", "owner", o);
            return false;
        }
        try {
            Object sb = service();
            sb.getClass().getMethod("setIcon", String.class, String.class, int.class, int.class, String.class)
                    .invoke(sb, slot, pkg, icon, 0, DESC);
        } catch (Throwable e) {
            log.write("indicator", "result", "set_failed", "msg", String.valueOf(e));
            return false;
        }
        shown = ours().equals(owner());
        log.write("indicator", "result", shown ? "shown" : "not_confirmed");
        return shown;
    }

    /** 숨김. 그 자리가 우리 것일 때만 지운다. */
    void hide() {
        if (!shown) return;
        shown = false;
        if (!ours().equals(owner())) {
            log.write("indicator", "result", "not_ours_on_hide");
            return;
        }
        try {
            Object sb = service();
            sb.getClass().getMethod("removeIcon", String.class).invoke(sb, slot);
            log.write("indicator", "result", "hidden");
        } catch (Throwable e) {
            log.write("indicator", "result", "remove_failed", "msg", String.valueOf(e));
        }
    }

    /** 표시 중인데 자리를 잃었는지(시스템이 덮어씀). 잃었으면 다시 올리지 않는다. 조회 실패는 잃은 것으로 보지 않는다. */
    boolean lost() {
        if (!shown) return false;
        String o = owner();
        if (o == null || o.equals(ours())) return false;
        shown = false;
        gaveUp = true;
        log.write("indicator", "result", "lost", "owner", o);
        return true;
    }

    /** 이전 세션이 남긴 우리 아이콘(점 또는 예전 원)을 정리한다(nrctl start 대응, DESIGN §5.8 남는 점). */
    void cleanupLeftover() {
        String o = owner();
        if (o == null) return;
        if (o.equals(ours()) || o.equals(FALLBACK_PKG + "/" + FALLBACK_ICON) || o.startsWith("nrc.companion/")) {
            try {
                Object sb = service();
                sb.getClass().getMethod("removeIcon", String.class).invoke(sb, slot);
                log.write("indicator", "result", "leftover_removed", "owner", o);
            } catch (Throwable e) {
                log.write("indicator", "result", "leftover_remove_failed", "msg", String.valueOf(e));
            }
        }
    }

    private static Object service() throws Exception {
        IBinder b = Phone.service("statusbar");
        return Class.forName("com.android.internal.statusbar.IStatusBarService$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, b);
    }
}
