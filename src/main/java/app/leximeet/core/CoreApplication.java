package app.leximeet.core;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

// 正式启动和容器生命周期集成测试共享同一入口，避免测试另一份简化装配。
final class CoreApplication {
  private CoreApplication() {}

  static ConfigurableApplicationContext start(CoreOptions options, StartupTiming timing) {
    timing.frameworkStarting();
    SpringApplication application = new SpringApplication(CoreConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setBannerMode(Banner.Mode.OFF);
    application.setLogStartupInfo(false);
    application.setLazyInitialization(false);
    application.setAddCommandLineProperties(false);
    application.addInitializers(
        context -> {
          context.getBeanFactory().registerSingleton("coreOptions", options);
          context.getBeanFactory().registerSingleton("startupTiming", timing);
        });
    ConfigurableApplicationContext context = application.run();
    timing.frameworkReady();
    // coreServer 的 initMethod 已在 refresh 期间完成，读取 bean 不会把创建成本延后到首个请求。
    context.getBean(CoreServer.class);
    timing.ready();
    return context;
  }
}
