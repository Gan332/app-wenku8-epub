import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.wenku8.epubstudio',
  appName: '文库 EPUB 工坊',
  webDir: 'public',
  server: {
    androidScheme: 'https',
    cleartext: false,
  },
  loggingBehavior: 'none',
};

export default config;
