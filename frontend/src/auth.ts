import { ref } from 'vue'

export interface AuthUser { username:string; displayName:string; admin:boolean; permissions:string[]; totpEnabled?:boolean; requiresTotpSetup?:boolean }
function read():AuthUser|null{try{const value=localStorage.getItem('release_user');return value?JSON.parse(value) as AuthUser:null}catch{return null}}
export const authUser=ref<AuthUser|null>(read())
export function setAuthUser(user:AuthUser){authUser.value=user;localStorage.setItem('release_user',JSON.stringify(user))}
export function clearAuth(){authUser.value=null;localStorage.removeItem('release_user');localStorage.removeItem('release_token')}
export function hasMenu(key:string){return Boolean(authUser.value?.admin||authUser.value?.permissions?.includes(key))}
