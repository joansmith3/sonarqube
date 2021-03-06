/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.core.issue.workflow;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.commons.lang.time.DateUtils;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.sonar.api.issue.DefaultTransitions;
import org.sonar.api.issue.Issue;
import org.sonar.api.rule.RuleKey;
import org.sonar.core.issue.DefaultIssue;
import org.sonar.core.issue.IssueChangeContext;
import org.sonar.core.issue.IssueUpdater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

public class IssueWorkflowTest {

  IssueUpdater updater = new IssueUpdater();
  IssueWorkflow workflow = new IssueWorkflow(new FunctionExecutor(updater), updater);

  @Test
  public void init_state_machine() {
    assertThat(workflow.machine()).isNull();
    workflow.start();
    assertThat(workflow.machine()).isNotNull();
    assertThat(workflow.machine().state(Issue.STATUS_OPEN)).isNotNull();
    assertThat(workflow.machine().state(Issue.STATUS_CONFIRMED)).isNotNull();
    assertThat(workflow.machine().state(Issue.STATUS_CLOSED)).isNotNull();
    assertThat(workflow.machine().state(Issue.STATUS_REOPENED)).isNotNull();
    assertThat(workflow.machine().state(Issue.STATUS_RESOLVED)).isNotNull();
    workflow.stop();
  }

  @Test
  public void list_statuses() {
    workflow.start();
    // order is important for UI
    assertThat(workflow.statusKeys()).containsSequence(Issue.STATUS_OPEN, Issue.STATUS_CONFIRMED, Issue.STATUS_REOPENED, Issue.STATUS_RESOLVED, Issue.STATUS_CLOSED);
  }

  @Test
  public void list_out_transitions_from_status_open() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue().setStatus(Issue.STATUS_OPEN);
    List<Transition> transitions = workflow.outTransitions(issue);
    assertThat(keys(transitions)).containsOnly("confirm", "falsepositive", "resolve", "wontfix");
  }

  @Test
  public void list_out_transitions_from_status_confirmed() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue().setStatus(Issue.STATUS_CONFIRMED);
    List<Transition> transitions = workflow.outTransitions(issue);
    assertThat(keys(transitions)).containsOnly("unconfirm", "falsepositive", "resolve", "wontfix");
  }

  @Test
  public void list_out_transitions_from_status_resolved() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue().setStatus(Issue.STATUS_RESOLVED);
    List<Transition> transitions = workflow.outTransitions(issue);
    assertThat(keys(transitions)).containsOnly("reopen");
  }

  @Test
  public void list_out_transitions_from_status_reopen() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue().setStatus(Issue.STATUS_REOPENED);
    List<Transition> transitions = workflow.outTransitions(issue);
    assertThat(keys(transitions)).containsOnly("confirm", "resolve", "falsepositive", "wontfix");
  }

  @Test
  public void list_no_out_transition_from_status_closed() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue().setStatus(Issue.STATUS_CLOSED).setRuleKey(RuleKey.of("java", "R1  "));
    List<Transition> transitions = workflow.outTransitions(issue);
    assertThat(transitions).isEmpty();
  }

  @Test
  public void list_out_transitions_from_status_closed_on_manual_issue() {
    workflow.start();

    // Manual issue because of reporter
    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setStatus(Issue.STATUS_CLOSED)
      .setRuleKey(RuleKey.of("manual", "Performance"))
      .setReporter("simon");

    List<Transition> transitions = workflow.outTransitions(issue);
    assertThat(keys(transitions)).containsOnly("reopen");
  }

  @Test
  public void fail_if_unknown_status_when_listing_transitions() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue().setStatus("xxx");
    try {
      workflow.outTransitions(issue);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Unknown status: xxx");
    }
  }

  @Test
  public void do_automatic_transition() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setRuleKey(RuleKey.of("js", "S001"))
      .setResolution(Issue.RESOLUTION_FIXED)
      .setStatus(Issue.STATUS_RESOLVED)
      .setNew(false)
      .setBeingClosed(true);
    Date now = new Date();
    workflow.doAutomaticTransition(issue, IssueChangeContext.createScan(now));
    Assertions.assertThat(issue.resolution()).isEqualTo(Issue.RESOLUTION_FIXED);
    Assertions.assertThat(issue.status()).isEqualTo(Issue.STATUS_CLOSED);
    Assertions.assertThat(issue.closeDate()).isNotNull();
    Assertions.assertThat(issue.updateDate()).isEqualTo(DateUtils.truncate(now, Calendar.SECOND));
  }

  @Test
  public void close_open_dead_issue() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setResolution(null)
      .setStatus(Issue.STATUS_OPEN)
      .setNew(false)
      .setBeingClosed(true);
    Date now = new Date();
    workflow.doAutomaticTransition(issue, IssueChangeContext.createScan(now));
    Assertions.assertThat(issue.resolution()).isEqualTo(Issue.RESOLUTION_FIXED);
    Assertions.assertThat(issue.status()).isEqualTo(Issue.STATUS_CLOSED);
    Assertions.assertThat(issue.closeDate()).isNotNull();
    Assertions.assertThat(issue.updateDate()).isEqualTo(DateUtils.truncate(now, Calendar.SECOND));
  }

  @Test
  public void close_reopened_dead_issue() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setResolution(null)
      .setStatus(Issue.STATUS_REOPENED)
      .setNew(false)
      .setBeingClosed(true);
    Date now = new Date();
    workflow.doAutomaticTransition(issue, IssueChangeContext.createScan(now));
    Assertions.assertThat(issue.resolution()).isEqualTo(Issue.RESOLUTION_FIXED);
    Assertions.assertThat(issue.status()).isEqualTo(Issue.STATUS_CLOSED);
    Assertions.assertThat(issue.closeDate()).isNotNull();
    Assertions.assertThat(issue.updateDate()).isEqualTo(DateUtils.truncate(now, Calendar.SECOND));
  }

  @Test
  public void close_confirmed_dead_issue() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setResolution(null)
      .setStatus(Issue.STATUS_CONFIRMED)
      .setNew(false)
      .setBeingClosed(true);
    Date now = new Date();
    workflow.doAutomaticTransition(issue, IssueChangeContext.createScan(now));
    Assertions.assertThat(issue.resolution()).isEqualTo(Issue.RESOLUTION_FIXED);
    Assertions.assertThat(issue.status()).isEqualTo(Issue.STATUS_CLOSED);
    Assertions.assertThat(issue.closeDate()).isNotNull();
    Assertions.assertThat(issue.updateDate()).isEqualTo(DateUtils.truncate(now, Calendar.SECOND));
  }

  @Test
  public void fail_if_unknown_status_on_automatic_trans() {
    workflow.start();

    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setResolution(Issue.RESOLUTION_FIXED)
      .setStatus("xxx")
      .setNew(false)
      .setBeingClosed(true);
    try {
      workflow.doAutomaticTransition(issue, IssueChangeContext.createScan(new Date()));
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Unknown status: xxx [issue=ABCDE]");
    }
  }

  @Test
  public void flag_as_false_positive() {
    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setStatus(Issue.STATUS_OPEN)
      .setRuleKey(RuleKey.of("squid", "AvoidCycle"))
      .setAssignee("morgan");

    workflow.start();
    workflow.doTransition(issue, DefaultTransitions.FALSE_POSITIVE, IssueChangeContext.createScan(new Date()));

    Assertions.assertThat(issue.resolution()).isEqualTo(Issue.RESOLUTION_FALSE_POSITIVE);
    Assertions.assertThat(issue.status()).isEqualTo(Issue.STATUS_RESOLVED);

    // should remove assignee
    Assertions.assertThat(issue.assignee()).isNull();
  }

  @Test
  public void wont_fix() {
    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setStatus(Issue.STATUS_OPEN)
      .setRuleKey(RuleKey.of("squid", "AvoidCycle"))
      .setAssignee("morgan");

    workflow.start();
    workflow.doTransition(issue, DefaultTransitions.WONT_FIX, IssueChangeContext.createScan(new Date()));

    Assertions.assertThat(issue.resolution()).isEqualTo(Issue.RESOLUTION_WONT_FIX);
    Assertions.assertThat(issue.status()).isEqualTo(Issue.STATUS_RESOLVED);

    // should remove assignee
    Assertions.assertThat(issue.assignee()).isNull();
  }

  @Test
  public void manual_issues_be_resolved_then_closed() {
    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setStatus(Issue.STATUS_OPEN)
      .setRuleKey(RuleKey.of(RuleKey.MANUAL_REPOSITORY_KEY, "Performance"));

    workflow.start();

    assertThat(workflow.outTransitions(issue)).containsOnly(
      Transition.create("confirm", "OPEN", "CONFIRMED"),
      Transition.create("resolve", "OPEN", "RESOLVED"),
      Transition.create("falsepositive", "OPEN", "RESOLVED"),
      Transition.create("wontfix", "OPEN", "RESOLVED")
      );

    workflow.doTransition(issue, "resolve", mock(IssueChangeContext.class));
    Assertions.assertThat(issue.resolution()).isEqualTo("FIXED");
    Assertions.assertThat(issue.status()).isEqualTo("RESOLVED");

    assertThat(workflow.outTransitions(issue)).containsOnly(
      Transition.create("reopen", "RESOLVED", "REOPENED")
      );

    workflow.doAutomaticTransition(issue, mock(IssueChangeContext.class));
    Assertions.assertThat(issue.resolution()).isEqualTo("FIXED");
    Assertions.assertThat(issue.status()).isEqualTo("CLOSED");
  }

  @Test
  public void manual_issues_be_confirmed_then_kept_open() {
    // Manual issue because of reporter
    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setStatus(Issue.STATUS_OPEN)
      .setRuleKey(RuleKey.of("manual", "Performance"))
      .setReporter("simon");

    workflow.start();

    assertThat(workflow.outTransitions(issue)).containsOnly(
      Transition.create("confirm", "OPEN", "CONFIRMED"),
      Transition.create("resolve", "OPEN", "RESOLVED"),
      Transition.create("falsepositive", "OPEN", "RESOLVED"),
      Transition.create("wontfix", "OPEN", "RESOLVED")
      );

    workflow.doTransition(issue, "confirm", mock(IssueChangeContext.class));
    Assertions.assertThat(issue.resolution()).isNull();
    Assertions.assertThat(issue.status()).isEqualTo("CONFIRMED");

    assertThat(workflow.outTransitions(issue)).containsOnly(
      Transition.create("unconfirm", "CONFIRMED", "REOPENED"),
      Transition.create("resolve", "CONFIRMED", "RESOLVED"),
      Transition.create("falsepositive", "CONFIRMED", "RESOLVED"),
      Transition.create("wontfix", "CONFIRMED", "RESOLVED")
      );

    // keep confirmed and unresolved
    workflow.doAutomaticTransition(issue, mock(IssueChangeContext.class));
    Assertions.assertThat(issue.resolution()).isNull();
    Assertions.assertThat(issue.status()).isEqualTo("CONFIRMED");

    // unconfirm
    workflow.doTransition(issue, "unconfirm", mock(IssueChangeContext.class));
    Assertions.assertThat(issue.resolution()).isNull();
    Assertions.assertThat(issue.status()).isEqualTo("REOPENED");
  }

  @Test
  public void manual_issue_on_removed_rule_be_closed() {
    // Manual issue because of reporter
    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setStatus(Issue.STATUS_OPEN)
      .setRuleKey(RuleKey.of("manual", "Performance"))
      .setReporter("simon")
      .setBeingClosed(true)
      .setOnDisabledRule(true);

    workflow.start();

    workflow.doAutomaticTransition(issue, mock(IssueChangeContext.class));
    Assertions.assertThat(issue.resolution()).isEqualTo("REMOVED");
    Assertions.assertThat(issue.status()).isEqualTo("CLOSED");
  }

  @Test
  public void manual_issue_on_removed_component_be_closed() {
    // Manual issue because of reporter
    DefaultIssue issue = new DefaultIssue()
      .setKey("ABCDE")
      .setStatus(Issue.STATUS_OPEN)
      .setRuleKey(RuleKey.of("manual", "Performance"))
      .setReporter("simon")
      .setBeingClosed(true)
      .setOnDisabledRule(false);

    workflow.start();

    workflow.doAutomaticTransition(issue, mock(IssueChangeContext.class));
    Assertions.assertThat(issue.resolution()).isEqualTo("FIXED");
    Assertions.assertThat(issue.status()).isEqualTo("CLOSED");
  }

  private Collection<String> keys(List<Transition> transitions) {
    return Collections2.transform(transitions, new Function<Transition, String>() {
      @Override
      public String apply(@Nullable Transition transition) {
        return transition.key();
      }
    });
  }
}
