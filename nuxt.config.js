const pkg = require('./package.json')

export default {
  ssr: false,
  target: 'static',
  telemetry: false,
  env: {
    PROD: '1',
    ANDROID_APP_URL: 'https://play.google.com/store/apps/details?id=com.audiobookshelf.app',
    IOS_APP_URL: ''
  },

  publicRuntimeConfig: {
    version: pkg.version
  },

  head: {
    title: 'Audiobookshelf+',
    htmlAttrs: {
      lang: 'en'
    },
    meta: [{ charset: 'utf-8' }, { name: 'viewport', content: 'viewport-fit=cover, width=device-width, initial-scale=1, user-scalable=no, maximum-scale=1' }, { hid: 'description', name: 'description', content: '' }, { name: 'format-detection', content: 'telephone=no' }],
    script: [
      {
        src: '/libs/sortable.js'
      }
    ],
    link: [{ rel: 'icon', type: 'image/x-icon', href: '/favicon.ico' }]
  },

  css: ['@/assets/tailwind.css', '@/assets/app.css', '@/assets/rice-fonts.css'],

  plugins: [
    '@/plugins/server.js',
    '@/plugins/db.js',
    '@/plugins/authRenewal.js',
    '@/plugins/localStore.js',
    '@/plugins/theme.client.js',
    '@/plugins/rice.client.js',
    '@/plugins/init.client.js',
    '@/plugins/axios.js',
    '@/plugins/capacitor/index.js',
    '@/plugins/data.js',
    '@/plugins/diagnostics.client.js',
    '@/plugins/capacitor/AbsAudioPlayer.js',
    '@/plugins/nativeHttp.js',
    '@/plugins/toast.js',
    '@/plugins/constants.js',
    '@/plugins/haptics.js',
    '@/plugins/i18n.js'
  ],

  // Private build directory inside the checkout. For static targets `nuxt generate` ignores buildDir and uses
  // node_modules/.cache/nuxt (cli-generate.js), which a linked node_modules shares between checkouts: the generated store
  // entry then resolved another checkout's store/index.js. static.cacheDir is the setting that is honored
  // (see scripts/verify-frontend-source.js).
  static: {
    cacheDir: '.nuxt'
  },

  components: true,

  modules: ['@nuxtjs/axios'],

  axios: {},

  build: {
    postcss: {
      postcssOptions: {
        plugins: {
          tailwindcss: {},
          autoprefixer: {}
        }
      }
    },
    babel: {
      plugins: [['@babel/plugin-proposal-private-property-in-object', { loose: true }]]
    }
  }
}
