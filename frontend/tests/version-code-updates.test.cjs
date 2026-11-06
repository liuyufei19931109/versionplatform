const { runWithTranslations } = require('../test-support/i18n.cjs')
const assert=require('node:assert/strict'),{test}=require('node:test'),fs=require('node:fs'),path=require('node:path'),ts=require('typescript');
const sandbox={exports:{},URL};runWithTranslations(ts.transpileModule(fs.readFileSync(path.join(__dirname,'../src/versionCodeUpdates.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,sandbox);const api=sandbox.exports;
test('GitHub navigation only allows fixed HTTPS PR/commit routes',()=>{
 const valid='https://github.com/example-team/web-app/pull/120';assert.equal(api.githubEventUrl(valid),valid);
 const commit='https://github.com/example-team/web-app/commit/'+'a'.repeat(40);assert.equal(api.githubEventUrl(commit),commit);
 for(const url of ['javascript:alert(1)','http://github.com/a/b/pull/1','https://github.com.evil.test/a/b/pull/1','https://evil.test/a/b/pull/1','https://x:secret@github.com/a/b/pull/1','https://github.com:444/a/b/pull/1','https://github.com/a/b/pull/0','https://github.com/a/b/pull/1?redirect=evil','https://github.com/a/b/pull/1#anything','https://github.com/a/b/commit/123','https://github.com/a/b/compare/a...b','https://github.com/a/b/pull/1/../../settings'])assert.equal(api.githubEventUrl(url),undefined,url);
});
test('Unknown and malformed status cannot claim pending builds or use inherited object keys',()=>{
 for(const status of ['toString','constructor','__proto__','WHATEVER',null,{},1])assert.equal(api.codeStatus(status),'UNKNOWN');
 assert.equal(api.codeStatusLabel('UNKNOWN'),'尚未核对');assert.equal(api.codeBuildBlocked({status:'BRANCH_DELETED'}),true);assert.equal(api.codeBuildBlocked({status:'SERVICE_CHANGED'}),true);assert.equal(api.codeBuildBlocked({status:'UNKNOWN'}),false);
});
test('Disabled endpoint discards untrusted code data and malformed payload errors separately',()=>{
 assert.equal(api.normalizeCodeUpdates({enabled:false,items:[{serviceId:1,branch:'release',status:'PENDING_BUILD'}]}).items.length,0);
 for(const value of [null,undefined,'',{}, {enabled:true},{enabled:'true',items:[]}, {enabled:true,items:{}}])assert.throws(()=>api.normalizeCodeUpdates(value));
});
test('Normalize ignores invalid identity/environment and rejects unsafe link while retaining plain text',()=>{
 const result=api.normalizeCodeUpdates({enabled:true,items:[null,{serviceId:-1,branch:'release'}, {serviceId:2,branch:'release',status:'ALIEN',headSha:'not-a-sha',title:'<img onerror=x>',actor:'x'.repeat(200),eventUrl:'javascript:evil',environments:[{environmentName:'alien',status:'BUILT'},null,{environmentName:'test',status:'BUILT',buildTaskId:12}]}]});
 assert.equal(result.items.length,1);const item=result.items[0];assert.equal(item.status,'UNKNOWN');assert.equal(item.headSha,undefined);assert.equal(item.eventUrl,undefined);assert.equal(item.title,'<img onerror=x>');assert.equal(item.actor.length,100);assert.equal(item.environments.length,1);assert.equal(item.environments[0].environmentName,'test');assert.equal(item.environments[0].buildTaskId,12);
});
test('Watched branch status distinguishes unchanged code from a new commit that needs a build',()=>{
 const head='a'.repeat(40),baseline='b'.repeat(40);
 const normalize=(status,buildStatus,headSha=head)=>api.normalizeCodeUpdates({enabled:true,items:[{serviceId:1,branch:'release20261024',status,buildStatus,headSha,baselineSha:baseline}]}).items[0];
 const unchanged=normalize('NO_UPDATES','PENDING_BUILD',baseline);
 assert.equal(unchanged.status,'NO_UPDATES');assert.equal(unchanged.buildStatus,'PENDING_BUILD');assert.equal(unchanged.baselineSha,baseline);
 assert.equal(api.codeStatusLabel(unchanged.status),'暂无新提交');assert.equal(api.codeStatusType(unchanged.status),'info');assert.equal(api.codeBuildStatusLabel(unchanged.buildStatus),'尚未打包');assert.equal(api.codeBuildBlocked(unchanged),false);
 const pending=normalize('PENDING_BUILD','PENDING_BUILD');assert.equal(pending.status,'PENDING_BUILD');assert.equal(api.codeStatusLabel(pending.status),'有新提交，待打包');assert.equal(api.codeStatusType(pending.status),'warning');
 for(const status of ['BUILDING','BUILD_FAILED','BUILT']){const item=normalize(status,status);assert.equal(item.status,status);assert.equal(item.buildStatus,status);assert.equal(api.codeBuildBlocked(item),false);}
});
test('Legacy pending builds do not claim that the watched branch has new commits',()=>{
 for(const buildStatus of [undefined,null]){
  const item=api.normalizeCodeUpdates({enabled:true,items:[{serviceId:1,branch:'release20261024',status:'PENDING_BUILD',buildStatus,headSha:'a'.repeat(40)}]}).items[0];
  assert.equal(item.status,'UNKNOWN');assert.equal(item.buildStatus,'PENDING_BUILD');assert.equal(api.codeStatusLabel(item.status),'尚未核对');assert.equal(api.codeBuildBlocked(item),false);
 }
});
test('Environment build state does not imply new commits and unknown fields stay unverified',()=>{
 const item=api.normalizeCodeUpdates({enabled:true,items:[{serviceId:1,branch:'release20261024',status:'NO_UPDATES',buildStatus:'constructor',baselineSha:'not-a-sha',environments:[{environmentName:'dev',status:'PENDING_BUILD'},{environmentName:'test',status:'NO_UPDATES'}]}]}).items[0];
 assert.equal(item.buildStatus,'UNKNOWN');assert.equal(item.baselineSha,undefined);assert.equal(api.codeBuildStatusLabel(item.environments[0].status),'尚未打包');assert.equal(item.environments[1].status,'UNKNOWN');
 for(const status of ['constructor','__proto__',{},null,1])assert.equal(api.codeBuildStatus(status),'UNKNOWN');
});
