package com.adkernel.simulator;

import com.adkernel.simulator.config.AppConfig;
import com.adkernel.simulator.config.ConfigLoader;
import com.adkernel.simulator.config.FunnelConfig;
import com.adkernel.simulator.config.TrafficConfig;
import com.adkernel.simulator.run.AuthChecker;
import com.adkernel.simulator.run.LoadRunner;
import com.adkernel.simulator.run.ReconTool;

import java.nio.file.Path;

/**
 * 媒体模拟器入口（严格对手方：流量生产者 + 业务合法性裁判 + 转化回传与账单生成者）。
 *
 * <pre>
 * 用法：
 *   run &lt;profile&gt; [--config &lt;dir&gt;]     # 压测运行（smoke / acceptance / fault / daycurve）
 *   authcheck [--config &lt;dir&gt;]           # 鉴权契约探针（k04）
 *   recon --events &lt;file&gt; --bills &lt;dir&gt;  # 对账预演（自记事件 vs 账单）
 * </pre>
 */
public final class SimMain {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      usage();
      System.exit(2);
    }
    try {
      String command = args[0];
      String configDir = "config";
      String profile = null;
      String events = null;
      String bills = null;

      for (int i = 1; i < args.length; i++) {
        switch (args[i]) {
          case "--config" -> configDir = args[++i];
          case "--events" -> events = args[++i];
          case "--bills" -> bills = args[++i];
          default -> {
            if (profile == null) {
              profile = args[i];
            } else {
              usage();
              System.exit(2);
              return;
            }
          }
        }
      }

      switch (command) {
        case "run" -> {
          if (profile == null) {
            usage();
            System.exit(2);
          }
          Path dir = Path.of(configDir);
          AppConfig app = ConfigLoader.loadApp(dir);
          TrafficConfig traffic = ConfigLoader.loadTraffic(dir);
          FunnelConfig funnel = ConfigLoader.loadFunnel(dir);
          int code = new LoadRunner(app, traffic, funnel, profile).run();
          System.exit(code);
        }
        case "authcheck" -> {
          AppConfig app = ConfigLoader.loadApp(Path.of(configDir));
          System.exit(AuthChecker.run(app));
        }
        case "recon" -> {
          if (events == null || bills == null) {
            usage();
            System.exit(2);
          }
          System.exit(ReconTool.run(events, bills));
        }
        default -> {
          System.err.println("unknown command: " + command);
          usage();
          System.exit(2);
        }
      }
    } catch (Exception e) {
      System.err.println("[sim] fatal: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static void usage() {
    System.err.println("""
        usage:
          run <profile> [--config <dir>]      profiles: smoke | acceptance | fault | daycurve
          authcheck [--config <dir>]
          recon --events <file> --bills <dir>
        """);
  }
}
