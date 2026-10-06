package app.leximeet.core;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;

// 非 Web 的 Boot 组合根：数据库、领域服务、监控和回环网关都是容器管理的 eager bean。 显式装配免除组件扫描与自动配置探测；销毁顺序由依赖关系保证先停网关、后关数据库。
@SpringBootConfiguration(proxyBeanMethods = false)
class CoreConfiguration {
  @Bean
  Clock clock(CoreOptions options) {
    if (options.testClockFile() != null && !options.profile().equals("test"))
      throw new IllegalArgumentException("可变业务时钟只允许隔离 test profile");
    return options.testClockFile() == null
        ? Clock.systemDefaultZone()
        : new FileBusinessClock(options.dataDir(), options.testClockFile());
  }

  @Bean
  Clock connectionClock() {
    return Clock.systemDefaultZone();
  }

  @Bean
  RuntimeDiagnostics runtimeDiagnostics(StartupTiming timing) {
    return new RuntimeDiagnostics(timing);
  }

  @Bean(destroyMethod = "close")
  Database database(CoreOptions options) throws Exception {
    return new Database(options.dataDir());
  }

  @Bean(destroyMethod = "")
  LeximeetService leximeetService(
      Database database,
      @Qualifier("clock") Clock clock,
      @Qualifier("connectionClock") Clock connectionClock,
      RuntimeDiagnostics diagnostics)
      throws Exception {
    return new LeximeetService(database, clock, connectionClock, diagnostics);
  }

  @Bean
  LmcpService lmcpService(LeximeetService service) {
    return service.lmcp();
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  CoreServer coreServer(LeximeetService service, CoreOptions options) throws Exception {
    if (options.seedCapacity() > 0)
      service.seedCapacity(options.seedCapacity(), options.seedNoteLength());
    return new CoreServer(service, options.token(), options.seedDemo());
  }
}
