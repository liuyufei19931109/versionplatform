package com.local.release.service;

import com.local.release.domain.ModelEnums.StepType;
import com.local.release.domain.ModelEnums.TaskStatus;
import com.local.release.repo.DeploymentEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import static com.local.release.domain.ModelEnums.TaskStatus.*;

/** A successful task proves its recorded targets were deployed. Failed and legacy
 * unscoped tasks cannot establish the version on an individual machine. */
public final class DeploymentReview {
  private DeploymentReview() {}

  public enum Action { ALLOW, CONFIRM, ACTIVE }
  public enum State { FIRST, CHANGE, REPEAT, ROLLBACK, UNKNOWN }
  public record Stamp(Long taskId, Long artifactId, Long buildTaskId, String actor,
      LocalDateTime at, TaskStatus status, List<Long> serverIds, boolean scopeKnown) {
    boolean mayCover(Long id) { return !scopeKnown || serverIds.contains(id); }
    boolean active() { return status == PENDING || status == RUNNING; }
  }
  public record History(Long artifactId, String environment, Stamp latestSuccess, Stamp latestAttempt) {}
  public record Node(Long serverId, State state, Stamp latestSuccess, Stamp latestAttempt) {}
  public record Result(Action action, String confirmationToken, List<Node> nodes, List<Stamp> activeTasks) {
    public void verify(String supplied) {
      if (action == Action.ACTIVE) throw new Conflict(this);
      if (!Objects.equals(confirmationToken, supplied)) throw new Conflict(this);
    }
    public Optional<Long> exactActiveTask(List<Long> ids) {
      var target = ids.stream().distinct().sorted().toList();
      return activeTasks.stream().filter(t -> t.scopeKnown() && t.serverIds().equals(target)).map(Stamp::taskId).findFirst();
    }
  }
  public static final class Conflict extends IllegalArgumentException {
    private final Result result;
    Conflict(Result result) {
      super(result.action == Action.ACTIVE ? "该制品在所选节点已有发布任务，请查看任务 #" + result.activeTasks.getFirst().taskId
          : "请核对目标节点最近发布的版本后再次确认");
      this.result = result;
    }
    public Result result() { return result; }
  }

  private record Observed(String environment, Stamp stamp, boolean uncertain) {}
  private record Key(Long artifactId, String environment) {}
  private static final Comparator<Observed> NEWEST_FIRST =
      Comparator.comparing((Observed o) -> o.stamp.at, Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(o -> o.stamp.taskId).reversed();

  public static List<History> history(List<DeploymentEvent> events) {
    Map<Key, History> result = new LinkedHashMap<>();
    for (var observation : events.stream().map(DeploymentReview::observe).sorted(NEWEST_FIRST).toList()) {
      Stamp stamp = observation.stamp;
      if (stamp.artifactId == null) continue;
      var key = new Key(stamp.artifactId, observation.environment);
      var prior = result.get(key);
      var attempt = prior == null ? stamp : prior.latestAttempt;
      var success = prior == null ? null : prior.latestSuccess;
      if (success == null && stamp.status == SUCCESS) success = stamp;
      result.put(key, new History(key.artifactId, key.environment, success, attempt));
    }
    return List.copyOf(result.values());
  }

  public static Result evaluate(Long serviceId, Long artifactId, String environment, List<Long> ids, List<DeploymentEvent> events) {
    var targets = ids.stream().distinct().sorted().toList();
    if (targets.isEmpty()) throw new IllegalArgumentException("请选择发布节点");
    var observations = events.stream().filter(e -> environment.equals(e.environment()))
        .map(DeploymentReview::observe).sorted(NEWEST_FIRST).toList();
    var active = observations.stream().map(Observed::stamp)
        .filter(t -> artifactId.equals(t.artifactId) && t.active() && targets.stream().anyMatch(t::mayCover)).toList();
    var nodes = targets.stream().map(id -> reviewNode(id, artifactId, observations)).toList();
    if (!active.isEmpty()) return new Result(Action.ACTIVE, null, nodes, active);
    boolean confirm = nodes.stream().anyMatch(node -> node.state == State.REPEAT || node.state == State.ROLLBACK || node.state == State.UNKNOWN);
    return new Result(confirm ? Action.CONFIRM : Action.ALLOW,
        confirm ? token(serviceId, artifactId, environment, nodes) : null, nodes, List.of());
  }

  private static Node reviewNode(Long id, Long artifactId, List<Observed> observations) {
    Stamp latestSuccess = null, latestRelevant = null;
    boolean previouslyPublished = false, unknown = false;
    for (Observed observation : observations) {
      Stamp stamp = observation.stamp;
      if (!stamp.mayCover(id)) continue;
      if (latestRelevant == null && (observation.uncertain || stamp.status == SUCCESS)) {
        latestRelevant = stamp;
        unknown = observation.uncertain || !stamp.scopeKnown || stamp.artifactId == null;
      }
      if (stamp.status == SUCCESS && stamp.scopeKnown) {
        if (latestSuccess == null) latestSuccess = stamp;
        if (artifactId.equals(stamp.artifactId)) previouslyPublished = true;
      }
    }
    State state;
    if (unknown) state = State.UNKNOWN;
    else if (latestSuccess == null) state = State.FIRST;
    else if (artifactId.equals(latestSuccess.artifactId)) state = State.REPEAT;
    else if (previouslyPublished) state = State.ROLLBACK;
    else state = State.CHANGE;
    return new Node(id, state, latestSuccess, latestRelevant);
  }

  private static Observed observe(DeploymentEvent e) {
    var ids = new TreeSet<Long>();
    if (e.targets() != null && !e.targets().isBlank()) {
      for (var part : e.targets().split(",", -1)) {
        try {
          long id = Long.parseLong(part.trim());
          if (id <= 0) throw new NumberFormatException();
          ids.add(id);
        } catch (NumberFormatException invalid) { ids.clear(); break; }
      }
    }
    LocalDateTime at = e.finishedAt() != null ? e.finishedAt() : e.startedAt() != null ? e.startedAt() : e.createdAt();
    boolean uncertain = (e.status() == FAILED || e.status() == CANCELLED) && e.startedAt() != null
        && (e.step() == null || e.step() == StepType.START_SERVICE || e.step() == StepType.HEALTH_CHECK);
    return new Observed(e.environment(), new Stamp(e.taskId(), e.artifactId(), e.buildTaskId(), e.actor(),
        at, e.status(), List.copyOf(ids), !ids.isEmpty()), uncertain);
  }

  private static String token(Long serviceId, Long artifactId, String environment, List<Node> nodes) {
    StringJoiner state = new StringJoiner("|").add(serviceId.toString()).add(artifactId.toString()).add(environment);
    for (Node n : nodes) {
      state.add(n.serverId.toString()).add(n.state.name());
      for (Stamp s : Arrays.asList(n.latestSuccess, n.latestAttempt)) {
        state.add(s == null ? "-" : s.taskId + ":" + s.status + ":" + s.at);
      }
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
}
