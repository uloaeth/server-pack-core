package com.serverpackcore.agent;
import com.serverpackcore.agent.UpdateHelper;
import java.nio.file.*;
public class Dump {
  public static void main(String[] a) throws Exception {
    Path dir = Path.of(a[0]);
    Files.createDirectories(dir.resolve("dir"));
    Files.createDirectories(dir.resolve("game dir/mods"));
    String agent = UpdateHelper.renderWindows(dir.resolve("dir"), dir.resolve("dir/spc-agent.jar"),
        dir.resolve("dir/spc-agent-new.jar"), dir.resolve("dir/spc-agent.jar.old"),
        dir.resolve("dir/spc-agent.jar.bad"), dir.resolve("dir/alive.txt"),
        Path.of("C:\\jdk\\bin\\javaw.exe"), dir.resolve("game dir"), "9.9.9", "update");
    String mod = UpdateHelper.renderModWindows(dir.resolve("dir"), dir.resolve("game dir/mods"),
        dir.resolve("dir/mod-new.jar"), dir.resolve("game dir/mods/serverpackcore-9.9.9.jar"),
        dir.resolve("dir/alive.txt"), "9.9.9", "update", 7L);
    byte[] bom = {(byte)0xFF, (byte)0xFE};
    Files.write(dir.resolve("agent.vbs"), concat(bom, agent.getBytes("UTF-16LE")));
    Files.write(dir.resolve("mod.vbs"), concat(bom, mod.getBytes("UTF-16LE")));
    System.out.println("ok");
  }
  static byte[] concat(byte[] a, byte[] b) {
    byte[] r = new byte[a.length + b.length];
    System.arraycopy(a, 0, r, 0, a.length);
    System.arraycopy(b, 0, r, a.length, b.length);
    return r;
  }
}
