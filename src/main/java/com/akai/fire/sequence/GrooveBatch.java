package com.akai.fire.sequence;

import java.util.*;
import java.util.function.*;

/** Serializes shared-cursor access, captures all originals and preflights every child before writing. */
final class GrooveBatch {
    interface Scheduler { void later(Runnable work,int millis); }
    private final Scheduler scheduler;
    private final Supplier<String> context;
    private final Consumer<String> status;
    private final List<GrooveSession> sessions=new ArrayList<>();
    private String capturedContext="";
    private GrooveSettings desired;
    private long generation,revision;
    private boolean busy,failed;
    private Runnable wake;
    private int moved, velocityEdits;
    private boolean fullApplyPending;
    private final Set<Integer> pendingRepairs=new HashSet<>();
    private int priority=-1;
    private List<Integer> workOrder=List.of();
    void prioritize(int index){if(index>=0&&index<sessions.size())priority=index;}
    private List<Integer> order(){
        List<Integer> result=new ArrayList<>();
        if(priority>=0&&priority<sessions.size())result.add(priority);
        for(int i=0;i<sessions.size();i++)if(i!=priority)result.add(i);
        return result;
    }
    private long lockRevision;
    private boolean recheckCapture, initialApply;

    GrooveBatch(Scheduler scheduler,Supplier<String> context,Consumer<String> status) {
        this.scheduler=scheduler;this.context=context;this.status=status;
    }
    boolean busy(){return busy;}
    boolean captured(){return !sessions.isEmpty();}
    boolean failed(){return failed;}
    String context(){return capturedContext;}
    void start(List<GrooveSession.Port> ports,String key,GrooveSettings settings) {
        discard();initialApply=true;fullApplyPending=true;capturedContext=key;desired=settings;revision++;busy=true;
        for(var port:ports)sessions.add(new GrooveSession(port,()->{if(wake!=null)wake.run();}));
        capture(generation,0);
    }
    void update(GrooveSettings settings) {
        desired=settings;revision++;fullApplyPending=true;
        if(!busy&&!failed)queue();
    }
    void notesEdited(){notesEdited(-1);}
    void notesEdited(int index) {
        prioritize(index);
        if(!captured()||failed)return;
        if(sessions.stream().anyMatch(session->session.original()==null)){recheckCapture=true;return;}
        generation++;revision++;wake=null;busy=false;
        for(var session:sessions)session.pauseForNotes();
    }
    void reconcile() {
        if(busy||!captured()||failed)return;
        busy=true;lockRevision=revision;
        reconcile(generation,order(),0);
    }
    private void reconcile(long token,List<Integer> order,int offset) {
        if(!valid(token))return;
        if(offset==order.size()) {
            busy=false;
            if(fullApplyPending||initialApply||revision!=lockRevision)queue();
            return;
        }
        int index=order.get(offset);
        GrooveSession session=sessions.get(index);
        session.reconcile(error->{
            if(!valid(token))return;
            if(!error.isEmpty()){busy=false;status.accept("Groove lock: "+error);return;}
            if(session.reconciledChange())pendingRepairs.add(index);
            // Local note edits can be repaired while the worker is already on this child.
            // Settings changes still require the full all-child preflight before writing.
            if(!pendingRepairs.contains(index)||fullApplyPending) {reconcile(token,order,offset+1);return;}
            session.prepare(desired,blocked->{
                if(!valid(token))return;
                if(!blocked.isEmpty()) {
                    busy=false;status.accept("Groove lock: Child "+(index+1)+": "+blocked);return;
                }
                if(fullApplyPending){reconcile(token,order,offset+1);return;}
                if(!session.preparedMoves()){pendingRepairs.remove(index);reconcile(token,order,offset+1);return;}
                status.accept("Updating child "+(index+1)+"/"+sessions.size());
                session.markBatchPending();session.preview();
                await(token,session,()->{pendingRepairs.remove(index);reconcile(token,order,offset+1);},false);
            });
        });
    }
    private boolean valid(long token) {
        if(token!=generation)return false;
        if(!capturedContext.equals(context.get())){fail("Scene/children changed; reopen groove");return false;}
        return !failed;
    }
    private void capture(long token,int index) {
        if(!valid(token))return;
        if(index==sessions.size()){busy=false;if(recheckCapture){recheckCapture=false;reconcile();}else queue();return;}
        status.accept("Reading child "+(index+1)+"/"+sessions.size());
        GrooveSession session=sessions.get(index);
        session.begin();
        await(token,session,()->capture(token,index+1),true);
    }
    private void await(long token,GrooveSession session,Runnable complete,boolean capturing) {
        Runnable check=()->{
            if(!valid(token))return;
            var state=session.state();
            if(state==GrooveSession.State.CAPTURING||state==GrooveSession.State.WRITING)return;
            if((capturing&&state==GrooveSession.State.STAGED)||(!capturing&&state==GrooveSession.State.PREVIEW)) {
                wake=null;scheduler.later(complete,0);
            } else if(state==GrooveSession.State.CONFLICT||state==GrooveSession.State.CLOSED
                    ||session.message().startsWith("Blocked:"))fail(session.message());
        };
        wake=check;
        // preview has a queued preflight before WRITING; callbacks drive subsequent checks.
        check.run();
    }
    private void queue() {
        long token=generation,edit=revision;
        scheduler.later(()->{
            if(!valid(token)||edit!=revision||busy||sessions.isEmpty())return;
            busy=true;workOrder=order();GrooveSettings snapshot=desired;
            preflight(token,edit,snapshot,0);
        },100);
    }
    private void preflight(long token,long edit,GrooveSettings settings,int index) {
        if(!valid(token))return;
        if(edit!=revision){busy=false;queue();return;}
        if(index==sessions.size()){moved=0;velocityEdits=0;write(token,edit,0);return;}
        int child=workOrder.get(index);
        sessions.get(child).prepare(settings,error->{
            if(!valid(token))return;
            if(!error.isEmpty()) {
                if(sessions.get(child).state()==GrooveSession.State.CONFLICT)fail("Child "+(child+1)+": "+error);
                else {busy=false;status.accept("Child "+(child+1)+": "+error);}
                return;
            }
            preflight(token,edit,settings,index+1);
        });
    }
    private void write(long token,long edit,int index) {
        if(!valid(token))return;
        if(index==sessions.size()) {
            initialApply=false;if(edit==revision){fullApplyPending=false;pendingRepairs.clear();}
            busy=false;status.accept(sessions.size()+" clips: "+(velocityEdits>0?moved+" notes updated":desired.velocity().active()?"Timing/velocity confirmed":desired.depth()==0?"Original timing":moved==0?"No notes moved (1/64 beat steps)":moved+" notes moved"));
            if(edit!=revision)queue();return;
        }
        int child=workOrder.get(index);
        GrooveSession session=sessions.get(child);
        if(!session.preparedMoves()){write(token,edit,index+1);return;}
        status.accept("Updating child "+(child+1)+"/"+sessions.size());
        // Disable the previous PREVIEW state until the new queued write completes.
        session.markBatchPending();
        session.preview();
        await(token,session,()->{moved+=session.lastMoveCount();velocityEdits+=session.lastVelocityCount();write(token,edit,index+1);},false);
    }
    private void fail(String reason) {
        failed=true;busy=false;wake=null;
        for(var session:sessions)session.invalidate("group edit stopped");
        status.accept(reason+"; reopen groove");
    }
    void discard() {
        generation++;revision++;wake=null;busy=false;failed=false;
        for(var session:sessions)session.keepHostEdits();
        sessions.clear();pendingRepairs.clear();capturedContext="";recheckCapture=false;initialApply=false;fullApplyPending=false;priority=-1;
    }
}
