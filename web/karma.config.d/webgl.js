// Chrome without a GPU it may use renders WebGL 2 with SwiftShader, in software, only when told it may.
// With -PwebTestsOnGpu it renders on the machine's GPU instead, through ANGLE on Vulkan: ChromeHeadless would add
// --disable-gpu, so that launcher starts Chrome itself in its headless mode.
config.set({
    customLaunchers: {
        ChromeHeadlessWebGl2: {
            base: "ChromeHeadless",
            flags: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
        },
        ChromeGpuWebGl2: {
            base: "Chrome",
            flags: [
                "--headless=new",
                "--enable-gpu",
                "--ignore-gpu-blocklist",
                "--use-angle=vulkan",
                "--enable-features=Vulkan",
            ],
        },
    },
    browsers: [process.env.DOG_VISION_TESTS_ON_GPU === "true" ? "ChromeGpuWebGl2" : "ChromeHeadlessWebGl2"],
});
