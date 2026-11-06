import { createApp, watchEffect } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import es from 'element-plus/es/locale/lang/es'
import { language, syncPageLanguage } from './i18n'
import 'element-plus/dist/index.css'
import './styles.css'
import App from './App.vue'
import router from './router'

const componentLocales = { 'zh-CN': zhCn, es, en }
syncPageLanguage()
watchEffect(() => { document.documentElement.lang = language.value })
createApp(App).use(router).use(ElementPlus, { get locale() { return componentLocales[language.value] } }).mount('#app')
