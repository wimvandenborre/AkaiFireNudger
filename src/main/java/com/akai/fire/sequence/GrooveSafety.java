package com.akai.fire.sequence;

import java.util.*;
import static com.akai.fire.sequence.GrooveEngine.*;

/** Whole-batch preflight and intermediate ordering, independent of the host. */
final class GrooveSafety {
    record Capabilities(double quantum, double sequencerGrid, boolean exactStarts, boolean complete,
                        boolean stableBinding, boolean preservesProperties, boolean tails,
                        boolean existingOverlaps, boolean playingPreview, boolean drumCellMode) {
        Capabilities(double quantum, double sequencerGrid, boolean exactStarts, boolean complete,
                     boolean stableBinding, boolean preservesProperties, boolean tails,
                     boolean existingOverlaps, boolean playingPreview) {
            this(quantum, sequencerGrid, exactStarts, complete, stableBinding, preservesProperties,
                    tails, existingOverlaps, playingPreview, false);
        }
        String blocked() {
            if (!exactStarts && !drumCellMode) return "Exact note starts unavailable";
            if (!complete && !drumCellMode) return "Complete note read unavailable";
            if (!stableBinding) return "Stable clip binding unavailable";
            if (!preservesProperties) return "Lossless note preservation unverified";
            if (!Double.isFinite(quantum) || quantum <= 0 || !Double.isFinite(sequencerGrid) || sequencerGrid <= 0)
                return "Invalid host timing precision";
            return "";
        }
    }
    record Move(Note from, Note to) {}
    record Result(List<Note> targets, List<Move> moves, double maxRoundingError, String blocked) {
        Result { targets=List.copyOf(targets); moves=List.copyOf(moves); }
        boolean allowed() { return blocked.isEmpty(); }
    }
    static Result validate(List<Note> originals, List<Note> current, Plan plan,
                           double loopStart, double loopLength, GrooveSettings settings,
                           Capabilities caps, boolean playing, boolean recording, boolean restoring) {
        String blocked = caps.blocked();
        if (!blocked.isEmpty()) return rejected(blocked);
        if (!restoring && caps.drumCellMode && settings.quantize() != 0) return rejected("Quantize needs exact starts; use timing shapes");
        if (recording) return rejected("Recording clip: edits blocked");
        if (playing && !caps.playingPreview) return rejected("Stop transport before writing");
        if (!restoring && !plan.continuous() && !settings.resetEachLoop())
            return rejected("Cycle does not fit loop; select Reset each loop");
        Map<String,Note> original = index(originals), actual = index(current);
        if (!actual.keySet().equals(original.keySet())) return rejected("Note identities changed");
        List<Note> targets = new ArrayList<>();
        double error = 0, end = loopStart + loopLength;
        for (Change c : plan.changes()) {
            Note before = original.get(c.original().id()), target = c.target();
            if (before == null || !before.equals(c.original())) return rejected("Plan does not use original snapshot");
            boolean changed = target.start() != before.start();
            if (!restoring && changed) {
                // In-place translation preserves any original residual; round displacement once.
                double rounded = before.start() + GrooveEngine.nearestEarlier((target.start()-before.start()) / caps.quantum) * caps.quantum;
                error = Math.max(error, Math.abs(rounded-target.start()));
                target = target.at(rounded);
                if (target.start() == before.start() && !caps.drumCellMode) return rejected("Displacement is below host resolution");
            }
            if (target.start() != before.start()) {
                if (before.start() < loopStart || before.start() >= end) return rejected("Pickup/tail onset outside editable loop");
                if (target.start() < loopStart || target.start() >= end) return rejected("Moved onset crosses loop boundary");
                if (!caps.tails && (before.start()+before.duration() > end || target.start()+target.duration() > end))
                    return rejected("Loop-crossing note tail unsupported");
                double anchor = loopStart + nearestEarlier((before.start()-loopStart)/caps.sequencerGrid)*caps.sequencerGrid;
                double oldOffset = Math.abs(before.start()-anchor), newOffset = Math.abs(target.start()-anchor);
                if (!restoring && newOffset > .4*caps.sequencerGrid+1e-10 && newOffset >= oldOffset-1e-10)
                    return rejected("Exceeds existing 40% nudge-cell limit");
            }
            Note permitted = before.at(target.start());
            if (!restoring && !settings.bypass() && !before.protectedNote()
                    && c.eligibility() != Eligibility.OFF_GRID)
                permitted = settings.velocity().apply(permitted, c.gridIndex());
            if (!permitted.equals(target)) return rejected("Plan changes unsupported note properties");
            targets.add(target);
        }
        if (targets.size()!=originals.size() || !index(targets).keySet().equals(original.keySet())) return rejected("Incomplete plan");
        blocked = collisions(originals, targets, loopLength, caps.existingOverlaps);
        if (!blocked.isEmpty()) return rejected(blocked);
        List<Move> moves = order(current, targets, loopLength);
        if (moves == null) return rejected("No safe intermediate movement order");
        return new Result(targets, moves, error, "");
    }
    static Result rejected(String reason) { return new Result(List.of(),List.of(),0,reason); }
    static Map<String,Note> index(List<Note> notes) {
        Map<String,Note> map = new LinkedHashMap<>();
        for (Note n : notes) if(map.put(n.id(),n)!=null) throw new IllegalArgumentException("Duplicate note ID");
        return map;
    }
    static String collisions(List<Note> original, List<Note> target, double length, boolean allowExisting) {
        Map<String,Note> old = index(original);
        for(int i=0;i<target.size();i++) for(int j=i+1;j<target.size();j++) {
            Note a=target.get(i), b=target.get(j), oa=old.get(a.id()), ob=old.get(b.id());
            if(a.pitch()!=b.pitch() || a.channel()!=b.channel()) continue;
            if (Math.signum(a.start()-b.start()) != Math.signum(oa.start()-ob.start())) return "Same-pitch note order changed";
            boolean was=overlap(oa,ob,length), now=overlap(a,b,length);
            if(now && (!was || !allowExisting)) return "Same-pitch overlap (including loop seam)";
        }
        return "";
    }
    static boolean overlap(Note a, Note b, double length) {
        for(double shift : new double[]{-length,0,length})
            if(a.start()<b.start()+shift+b.duration()-1e-10 && b.start()+shift<a.start()+a.duration()-1e-10) return true;
        return false;
    }
    static List<Move> order(List<Note> current, List<Note> targets, double length) {
        Map<String,Note> live=index(current), destinations=index(targets);
        Set<String> todo = new LinkedHashSet<>();
        for(Note n:current) if(!n.equals(destinations.get(n.id()))) todo.add(n.id());
        List<Move> moves=new ArrayList<>();
        while(!todo.isEmpty()) {
            boolean progress=false;
            for(String id:List.copyOf(todo)) {
                Note from=live.get(id), to=destinations.get(id);
                boolean safe=true;
                for(Note neighbor:live.values()) if(!neighbor.id().equals(id) && neighbor.pitch()==to.pitch() && neighbor.channel()==to.channel()) {
                    if(Math.abs(to.start()-neighbor.start())<1e-10 ||
                            (overlap(to,neighbor,length) && !overlap(from,neighbor,length))) {safe=false;break;}
                }
                if(safe){
                    // Compute both targets from originals, but acknowledge timing before setting velocity.
                    Note positioned = from.at(to.start());
                    if (!from.equals(positioned)) moves.add(new Move(from,positioned));
                    if (!positioned.equals(to)) moves.add(new Move(positioned,to));
                    live.put(id,to);todo.remove(id);progress=true;
                }
            }
            if(!progress)return null;
        }
        return moves;
    }
}
