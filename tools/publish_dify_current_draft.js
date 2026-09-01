(async()=>{
  const app='200bb456-20bf-48ab-af38-c7b9e59ff070'
  const c=document.cookie.split('; ').find(v=>v.startsWith('csrf_token='))
  const csrf=c?decodeURIComponent(c.split('=').slice(1).join('=')):''
  const h={'Content-Type':'application/json','X-CSRF-Token':csrf}
  const draft=await (await fetch('/console/api/apps/'+app+'/workflows/draft',{credentials:'include',headers:h})).json()
  const graph=draft.graph||{}
  const body={graph,features:draft.features,environment_variables:draft.environment_variables,conversation_variables:draft.conversation_variables,rag_pipeline_variables:draft.rag_pipeline_variables,hash:draft.hash}
  const response=await fetch('/console/api/apps/'+app+'/workflows/publish',{method:'POST',credentials:'include',headers:h,body:JSON.stringify(body)})
  const result=await response.json().catch(()=>({}))
  return {status:response.status,ok:response.ok,nodeCount:(graph.nodes||[]).length,edgeCount:(graph.edges||[]).length,publishedId:result.data?.id||result.id||'',version:result.data?.version||result.version||'',message:result.message||result.code||''}
})()
