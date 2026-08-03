## Summarized at 2026-08-02T08:48:57.172802+00:00

<message type="human">`guide` 도구를 먼저 불러 작업 방식을 확인한다.

그다음 범위 `ALL`에서 원본문서 `sources/102/parsed/content.md` 1건을 위키로 반영한다. guide의 「원본문서 1건을 위키로 만들기」 순서를 그대로 따른다 — 문서를 읽고, 겹치는 페이지를 검색해 본문까지 읽고, 기존 페이지를 고치거나 새로 만들고, `index.md`를 갱신하고, 마지막에 `lint`를 부른다.

`lint`가 `error`를 내면 고치고 다시 부른다. error가 0이 될 때까지 끝내지 않는다.</message>
<message type="ai">
  <content>먼저 가이드를 확인하겠습니다.</content>
  <tool_call id="toolu_01VBKEvz1KS7525THncwFi2U" name="guide">{}</tool_call>
</message>
<message type="tool"># 사내 위키 편집 에이전트

너는 사내 위키를 만들고 유지하는 편집자다. 원본문서(공지·규정·인수인계)를 사내 구성원이 읽는
위키로 재구성한다.

## 구조

1. **원본문서** — 관리자가 업로드한 문서. **읽기 전용이고 지울 수 없다.**
   주소: `sources/{문서ID}/parsed/content.md`
2. **위키 페이지** — 네가 만들고 유지한다. 주소: `pages/{키}.md`
   키는 `create`가 발급한다. 제목이나 카테고리를 바꿔도 주소는 그대로다.
3. **목차** — `index.md`. 범위마다 하나. 항상 있다.
4. **범위(scope)** — 모든 도구가 받는 `scope` 인자. 공개 범위 하나가 위키 하나다.
   부서 조합에서 나오고 네가 만들 수 없다. 모르면 `list_scopes`를 먼저 부른다.

**카테고리는 경로에 없다.** DB 메타데이터이고 frontmatter의 `category`에 적는다. 그래서
카테고리를 바꿔도 파일이 움직이지 않는다.

**같은 범위 안의 페이지는 열람 권한이 전부 같다.** 그래서 범위 안에서는 본문에 다른 페이지로
가는 링크를 써도 된다. 범위를 넘는 링크는 금지다 — 볼 권한 없는 사람에게 제목이 새어나간다.

**네 모든 변경은 작업 공간에 먼저 쌓인다.** 라이브 위키는 검증 뒤에 백엔드가 반영한다. 그래서
마음껏 쓰고 다시 읽고 고칠 수 있다.

### `index.md` — 허브

**원본문서를 처리할 때마다 갱신한다. 한 페이지만 고칠 수 있다면 이 페이지다.**

- 이 범위 위키가 무엇을 다루는지
- 원본문서 수와 페이지 수
- **핵심 내용** — 가장 중요한 것들. 각 항목은 해당 페이지로 링크한다
- **최근 변경** — 최근 5~10건

이 페이지가 갱신되지 않으면 다음 처리 때 어디에 무엇이 있는지 알 수 없고, 결국 문서 하나가
어디에도 반영되지 않은 채 사라진다.

## 카테고리

**모든 위키 페이지는 카테고리 하나에 속한다.** 카테고리가 목차의 축이다.

길이로 나누지 말고 **종류**로 나눈다. 두 축이 잘 듣는다.

- **개념** — 제도·원칙·절차·방침처럼 추상적인 것. `휴가 정책`, `보상 체계`, `온보딩 절차`
- **개체** — 조직·직무·시스템·양식처럼 가리킬 수 있는 것. `개발팀`, `근태 시스템`, `경비 정산 양식`

**`search(mode="list")`로 이미 쓰이는 카테고리를 먼저 확인한다.** 맞는 것이 있으면 그 이름을
**글자 그대로** 쓴다. 비슷한 이름을 새로 만들지 않는다(`복지 제도`와 `복리후생`을 따로 두지
않는다). 어디에도 안 맞을 때만 새로 만든다.

## 페이지 하나의 크기

**페이지는 주제 단위다. 원본문서의 절 단위가 아니다.** 문서 하나를 여러 페이지로 쪼개는 것이
기본이 아니라, 여러 문서에 흩어진 같은 주제를 하나로 모으는 것이 기본이다.

- **새 문서의 지식은 반드시 위키에 들어간다.** 기존 페이지에 `edit`으로 넣는 것이 1순위,
  넣을 페이지가 없으면 `create`가 정답이다. 아무것도 하지 않는 것은 그 내용이 **이미** 위키에
  있을 때만 유효한 결론이다. 주제가 다르다는 이유로 문서를 통째로 버리지 않는다.
- **한 문서를 여러 페이지로 나누려면, 나뉜 각각이 다른 문서의 내용까지 끌어와야 한다.** 한
  문서 안에서만 갈라지는 구분은 페이지 경계가 아니라 `##` 절 제목이다.
- **본문이 1,500자를 넘기지 못할 주제는, 흡수할 기존 페이지가 있으면 독립 페이지로 만들지 않고
  그 페이지의 절로 넣는다.** 흡수할 곳이 없으면 만든다 — 짧다는 이유로 지식을 버리는 것이 더 나쁘다.
- 같은 주제가 이미 여러 페이지에 흩어져 있으면 `merge`로 합친다.

## 작성 기준

**위키 페이지는 채팅 답변보다 훨씬 충실해야 한다.** 오래 남는 문서다.

### frontmatter — 필수

```yaml
---
title: 연차 휴가 산정
description: 입사일 기준 연차 발생과 이월 규칙
date: 2026-07-27
tags: [휴가, 근태, 인사]
category: 휴가 정책
---
```

`title`·`tags`(2개 이상)·`category`는 필수, `description`·`date`는 권장. `description`에는
각주 표시를 넣지 않는다 — 목차와 검색 결과에 `[^1]`이 그대로 찍힌다.

### 구조

- 첫 문단은 이 페이지가 무엇을 다루는지 요약한다. 제목을 `#`로 반복하지 않는다
- `##`로 절을 나누고, 필요하면 `###`
- **한 절에 한 가지 생각.** 사실은 목록으로, 종합은 문장으로

### 시각 요소 — 필수

**모든 페이지에 표나 다이어그램이 최소 1개 있어야 한다. 산문만 있는 페이지는 미완성이다.**

- **표** — 3개 이상 항목을 나열하거나 비교하면 표로
- **mermaid** — 절차·흐름·관계·조직도

````
```mermaid
graph LR
    A[신청] --&gt; B[팀장 승인] --&gt; C[인사 반영]
```
````

mermaid 파서는 엄격하다. 틀리면 코드 덩어리로 그대로 보인다.

- 노드 라벨에 `( ) [ ] { }`가 들어가면 반드시 인용한다: `A["비용 (5%)"]`
- `quadrantChart`·`xychart`의 축·사분면 라벨에는 괄호를 쓸 수 없다
- 다이어그램 안에 `$`나 LaTeX를 쓰지 않는다

### 근거 각주 — 필수

**모든 사실에 근거 각주를 단다.** 근거를 찾을 수 없는 내용은 쓰지 않는다.

```
연차는 입사일 기준으로 산정한다[^1]. 이월은 다음 해 3월까지다[^2].

[^1]: 인사규정.pdf, 3장 휴가 — "연차는 입사일을 기준으로 산정한다"
[^2]: 인사규정.pdf, 12쪽
```

규칙:

- **원본문서명을 전체로 적는다.** `read`가 「각주에 쓸 문서명」으로 알려주는 그 값을 쓴다
- **위치를 반드시 붙인다** — 절 제목 또는 쪽 번호. 문서명만 적은 각주는 근거가 아니다
- **가능하면 원문 문장을 `—` 뒤에 그대로 인용한다.** 옮겨 쓰지 말고 복사한다
- **주장 하나에 각주 하나.** 관계없는 주장을 한 각주로 묶지 않는다
- 각주 정의는 문서 끝에 모은다

`lint`가 각주의 문서·위치·인용문을 원본과 대조한다. 지어낸 위치나 바꿔 쓴 인용문은 잡힌다.

### 상호 참조

**관련 페이지가 있으면 본문에서 링크한다.** 링크가 관계 데이터의 유일한 입력원이다 —
링크하지 않으면 두 페이지는 서로를 모른다.

```markdown
&gt; **참고:** 업무 시간 중 교육은 [교육 및 도서 지원](pages/a3f2c1d4.md) 참고.
```

주소는 `create`가 알려준 것이나 `search`에 나온 것을 쓴다. 범위를 넘는 링크는 쓰지 않는다.

## 작업 흐름

### 원본문서 1건을 위키로 만들기

이게 기본 단위다. 문서 하나를 잡고 처음부터 끝까지 한다.

1. `read(scope, path="sources/...")` — 원본문서를 읽는다. PDF면 `pages`로 나눠 읽는다
2. `search(scope, mode="list")` — 이미 쓰이는 카테고리와 페이지를 본다
3. `search(scope, mode="search", query="...")` — 이 내용이 이미 어디 있는지 찾는다.
   키워드를 바꿔 여러 번 부른다
4. `read(scope, path="pages/...")` — **후보 페이지의 본문을 읽는다.** 목록이나 요약만 보고
   겹침을 판단하지 않는다. 이걸 건너뛰면 이미 있는 내용을 또 만든다
5. `edit` — 기존 페이지에 넣을 수 있으면 넣는다. 1순위
6. `create` — 기존 페이지로 표현되지 않는 지식만 새로 만든다
7. `merge` — 같은 주제가 흩어져 있으면 합친다
8. `edit`으로 `index.md` 갱신 — 원본문서 수, 핵심 내용, 최근 변경
9. `lint(scope)` — `error`가 남았으면 고치고 다시 부른다

원본문서 1건은 보통 페이지 2~5건을 건드린다. 하나도 안 건드리고 새로 만들기만 했으면 3·4번을
제대로 하지 않은 것이다.

### 쓰기 후 응답을 읽는다

`create`·`edit`·`append`·`merge`는 성공 메시지만 주지 않는다. **그 페이지를 참조하는 다른
페이지 목록을 같이 준다.** 내용이 바뀌어 그쪽도 손봐야 하면 지금 손본다. 이 되짚기를 무시하면
위키가 서로 모순되기 시작한다.

### 질문에 답하기

1. `search(scope, mode="search")`로 근거를 찾는다
2. 관련 페이지와 원본문서를 읽는다
3. 근거를 붙여 답한다
4. **근거를 못 찾으면 정확히 이렇게 답한다: 제공된 문서에서 확인할 수 없음**

## 금지

- 원본문서를 수정하거나 삭제하지 않는다
- 근거 없는 내용을 쓰지 않는다
- 범위를 넘는 본문 링크를 쓰지 않는다
- `index.md`를 지우지 않는다 (내용은 `edit`으로 고친다)
- 낡았을 뿐인 페이지를 `delete`하지 않는다 — `edit`으로 고친다. 되돌릴 수단이 없다

## 이 서버의 범위

- **ALL** — 원본문서 2건, 위키 1페이지</message>
<message type="ai">
  <content>이제 원본문서를 읽고, 현재 위키 목록을 파악하겠습니다.</content>
  <tool_call id="toolu_01XzhynbQoA71e7VACFKBoyc" name="read">{"scope": "ALL", "path": "sources/102/parsed/content.md"}</tool_call>
  <tool_call id="toolu_01JmNUXSb95bc9bq5xN6HStK" name="search">{"scope": "ALL", "mode": "list"}</tool_call>
</message>
<message type="tool">**08-compensation.md**
원본문서 | 문서 ID: 102 | 각주에 쓸 문서명: `08-compensation.md`
[보기](http://localhost:3000/wiki/ALL/sources/102/parsed/content.md)

---

---
title: Compensation
sidebar: Handbook
showTitle: true
---

## How it works

We have a set system for compensation as part of being transparent.

You can use our compensation calculator below to see what your compensation might look like when you're joining PostHog, and to see how it might develop over time:

&lt;CompensationCalculator initialJob="Product Engineer" /&gt;

We think the fastest possible shipping comes from a leaner and stronger team. We pay generously, so you'll work with the best people in the world.

Important:

-   If we are missing your country, it simply means we've not hired there before so we'd need to put together some data in advance of hiring you.
-   If you're considering applying to PostHog and the salary is the only blocker, then something is wrong with our model (as we aim to pay generously) for your circumstances in most cases. Please tell us as part of the hiring process and we will review things.

### Level

Level does not correlate with increased importance, but with impact within PostHog. Your level is _not_ a title – we don't believe in having a huge hierarchy of roles, as everyone needs to feel like the owner of the company that they are.

Very broadly, we think of the various levels as:
- Junior: contributes to small or function-specific projects (note, we rarely hire people at this level)
- Intermediate: owns projects with small to medium impact
- Senior: owns and drives high-impact projects or a whole product, decides what needs to happen and does everything necessary to get that done
- Staff: same as senior, but also _consistently_ owns and drives large projects that impact the entire company, not just their product, team or even function.
- Director: someone who is operating at the highest levels, usually a member of the Blitzscale team and usually a manager of managers
- Principal (same level as Director): same as Staff, but with extremely rare skills or operating at a very high level.

It's important to note that this is not a checklist. These descriptions are indicative, and there will always be a degree of judgement by the &lt;SmallTeam slug="blitzscale" /&gt; to decide which level you're at, also based on other people within PostHog.

### Step

Within each level, we believe there's a place to have incremental steps to allow for more flexibility. We define these as follows:

- *Learning*: Starting to match expectations.
- *Established*: Matching expectations.
- *Thriving*: Exceeding expectations.
- *Expert*: Exceeding expectations consistently.

With exception of team members at the very beginning of their career or where it is their first time in this type of role, we hire into the *Established* step by default. This will give everyone the opportunity to be set up for success and leave enough room for salary increases, without the need to move up in seniority. 

At pay review time, we compare your step to peers in similar roles at PostHog and decide whether to bump it up. Here's [how to move from one step or level to another](/handbook/people/career-progression).

### Benchmark
 
In line with our compensation philosophy, the benchmark for each role we are hiring for is based on the market rate in San Francisco. 

We use [Pave](https://www.pave.com/marketdata) as our main source for our salary benchmark and build a target range based on that data.

Because the engineering market is very competitive, and we think there is a 10x difference between an average and a top engineer, we pay near the top of market, which we define as being the 90th percentile, at the time of review. For other roles we still try to pay towards the top of market, which we define as 50th percentile + 20%.

### Location factor

Most of our location factors are based on GitLab's location factors. Location factors are based on _cost of market_, not cost of living. This means that we look at how much it typically costs to hire a person in that role in that location, not how much it costs to live there. This is why, for example, our location factor for San Francisco is the highest, even though there are several other places that are more expensive to live. 

We set a floor of 0.8 in the US, and 0.6 everywhere else, to avoid creating huge disparities in pay if someone happens to live in an exceptionally low cost of living country/state.

GitLab uses a combination of data from Economic Research Institute (ERI), Numbeo, Comptryx, Radford, Robert Half, and Dice to calculate what a fair market rate is for each location. [Read more](https://about.gitlab.com/handbook/total-rewards/compensation/compensation-calculator/#calculating-location-factors) on how GitLab calculates this location factor. 

The location factor takes your local exchange rate into account, so we don't have to keep updating exchange rates when they fluctuate. The floor also helps mitigate this. You will always be paid in your local currency, unless there is a very good reason not to (e.g. it is normal in your country to transact in USD).

### Executive compensation

For hiring into executive roles, we use a separate database of compensation benchmarks rather than this calculator. The terms of access to this (paid) database means that we're not able to share it publicly.

The benchmark data is all we use for executives. As a rule, executives are paid above average but not top of market.

The reason for less sophistication here is that we have very few executives, and only one for each role by definition. It's irrational to create a system so that, within a given benchmark, people are paid equally when there is just one person to consider!

## Pay reviews

We review pay proactively 2-3 times a year, and everybody on the team is reviewed at every pay review. You do not need to do anything - our goal is to keep your compensation at an appropriate level without you having to ask.

As we do these much more frequently than regular companies, team members should definitely not expect these to result in a change to their Step or Level each time - mostly they will stay the same. Additionally, team members will find that their Step, or place within a Step's range, will change more frequently than their Level.

Finally, we may change pay without editing Step or Level if the market rates for the underlying benchmark have gone up. When we review pay we don't take inflation into account, as this is already accounted for by market data. Thus, you won't get a yearly "inflation raise" as is typical in many localities, though our review process and benchmarking ensures your salary will remain in-line with the market.

We do also regularly increase benchmark levels when we are making a deliberate attempt to raise the bar in terms of hiring. This means that, when a benchmark is increased, it's not unusual for your level and step to come down. You will still get a pay increase, just not always at the same % increase as if the benchmark alone went up. 

### How the review process works

To make sure everyone has an equal chance at getting a pay rise, we do not factor in how frequently someone requests one. When increasing pay we only look at our calculator and performance. This helps us to be as inclusive as possible, as underrepresented groups are statistically [less likely](https://www.glassdoor.com/blog/do-race-gender-play-a-role-in-salary-negotiations/) to request a pay rise. 

We want to increase pay as frequently as we can in a proactive way, rather than putting the onus on the team member to negotiate every time. We don't make any changes outside of these reviews - if you change role for example, any changes will usually happen at the next closest review.

Any increases will be communicated by the relevant Blitzscale team member, as compensation is not a [manager's responsibility](/handbook/company/management) at PostHog.  If you need to talk to someone about your compensation or how the calculator works, you should ask the relevant member of the exec team in the first instance. 

You will only hear from them if there has been a change in your pay, not if it is staying the same. 

If you recently accepted an offer at PostHog and the benchmark changes between you accepting and joining PostHog, your pay will be re-assessed during the next pay review. Often this just means adjusting benchmark, step, and level - it is unlikely that your actual pay will go up.  

### Relocating

If you're planning on relocating, your salary may be adjusted (up or down) to your new location. This will be done at the next compensation review. If this represents an increase in pay, _we need to approve this change in advance_ - we cannot guarantee it is always possible, as our budgets may not allow it.

If you are nomading, we will set your location factor for the place that you are spending the most time in over the next 6 months. Our frequent compensation reviews mean that we can make adjustments reasonably frequently, but again any increase needs approval in advance. 

&gt; Please note that there are [a few countries](/handbook/people/hiring-process#countries-we-employ-people-in) that we don't employ people in. 

## Equity

It’s important to us that all PostHog employees can feel invested in the company’s success. Every one of us plays a critical role in the business and deserves a share in the company's success as we grow. When employees perform well, they contribute to the business doing well, and therefore should share a part of the increased financial value of the business.

As part of your compensation, you will receive share options in the company. We do not have a strict calculator here, but broadly you receive equity based on your role, level and location. Our general philosophy here is average equity, with extremely employee-friendly terms and options for liquidity through [secondary](/blog/how-secondaries-actually-work). 

Whilst the terms of options for _any company_ could vary if we were ever acquired, we have set them up with the following key terms which we believe are industry-leading in their friendliness to employees:

-   Standard 4-year vesting with a 1-year cliff
-   10 years to exercise your options in the event that you leave PostHog
-   Double trigger acceleration, which means if you are let go or forced to leave due to the company being acquired, you receive all of your options at that time
-   Vesting starts from your start date (not after a "probation period" or similar)
-   For UK-based team members, eligible options are part of the [EMI share options scheme](https://www.gov.uk/tax-employee-share-schemes/enterprise-management-incentives-emis) or the [CSOP scheme](https://www.gov.uk/tax-employee-share-schemes/company-share-option-plan), which are tax-advantaged

It can take time to approve options, as it requires a board meeting and company valuation. We can clarify the likely time frame at the time we're hiring you. Vesting will always start from when you joined PostHog, not from when you receive your option agreement. While we can commit to a particular number, we cannot commit to a particular strike price when offering share options, as the valuations are done by a third party and can vary depending on where we are in our funding cycle.

Check out our [share options FAQs](/handbook/people/share-options#frequently-asked-questions) to learn more. 

### Equity refreshes

Every employee will be eligible for equity refreshes each year you are working at PostHog. These grants are between 18%-25% of the value of a new grant for your current role. The percentage is based on your performance and can vary year by year. 

These equity refreshes will be decided at our pay review cycles, and are communicated by the relevant #team-blitzscale member at that time. Grants are approved quarterly by the board, though vesting is back-dated begins on the actual anniversary date. You'll be made aware of your grant being approved when you get an email from Carta regarding the grant. These refresher grants will be on the same terms as your original grant with a 12 month cliff, they will likely be subject to a different strike price due to changes in valuations.

Funding rounds disrupt when we are able to issue new grants, so approvals may be delayed if we are actively fundraising. 

## Probation period

We are fully committed to ensuring that you are set up for success, but also understand that it may take some time to determine whether or not there is a long term fit between you and PostHog. 

Subject to certain exceptions for sales roles and German employees mentioned below, the first 3 months of your employment with PostHog is a probation period. During this time, you can choose to end your contract with 1 week's notice. If we choose to end your contract, PostHog will pay you 4 weeks' base salary pay, but usually ask you to finish on the same day.

People in sales roles, such as Account Executives, have a 6 month probation period - this is to account for the fact that it can be difficult to establish whether or not someone is able to close contracts within their first 3 months, given sales cycles.

German employees also have a 6 month probation period - this is to align with market standard best practices and expectations for hiring in Germany, as it can be operationally difficult to part ways with German employees so we ask for as much information as possible to establish whether the hire is a good, mutual, long-term fit. During probation, either PostHog or the German employee may choose to end the employment contract with 1 month notice.

Your manager is responsible for monitoring and specifically reviewing your performance [throughout this initial period](/handbook/people/onboarding#306090-day-check-ins). If under-performance is a concern, or if there is any hesitation regarding the future at PostHog, this should be discussed immediately with you and your manager. 

At the end of your probation period, you won’t usually receive formal confirmation that you’ve passed probation, the default is no communication. By that point, you should already have a clear understanding of your performance and progress through your 30/60/90-day check-ins with your manager.

## Severance

At PostHog, average performance gets a generous severance.

If PostHog decides to end your contract after the first 3 months (6 months for sales roles), we will offer you a total of 4 months of base salary (which includes any time we need to give you under the law). To receive these benefits, we will ask that you sign a standard post-termination certificate or release. For our German teammates who have completed their 6 month probation, we will follow the local legal requirements for notice and severance, in line with what is typical in Germany.  In some cases, we might ask you to stop working right away and pay you instead of having you work through your notice period, or set up a "garden leave" depending on what is most appropriate for your location and contract. If the decision to leave is yours, then we generally just require 1 month of notice, though this can vary depending on your country's laws or the specifics of your contract.

If you are in a role with a commission/bonus component, you will be paid the amount you are owed as of your last day at PostHog. 

We have structured notice in this way as we believe it is in neither PostHog's nor your interest to lock you into a role that is no longer right for you due to financial considerations. This extended notice period only applies in the case of under-performance or a change in business needs - if your contract is terminated due to gross misconduct then you may be dismissed without notice. If this policy conflicts with the requirements of your local jurisdiction, then those local laws will take priority.

## Contracts

We currently operate our employment contracts in the three geographic regions where we have business entities: 

- United States of America
- United Kingdom
- Germany

This means, if you live in one of those countries, you will be directly employed by PostHog or the applicable subsidiary as an employee in one of our entities.

If you live outside the US, the UK or Germany, we use [Deel](https://app.letsdeel.com/) as our international employer of record. This means you are technically employed by Deel on our behalf. This doesn't affect your rights or benefits. 

In some cases, you may be an independent contractor, in which case you will invoice us monthly via Deel. Deel offers pretty much all countries and currencies. As a contractor, you will be responsible for your own taxes. 

## Payroll

In the UK and for international contractors, we run payroll monthly, on or before the last working day of the month. 

In the US, we run payroll twice a month, on the 15th and on the last day of the month. 

Deel runs payroll on the last working day of the month.
</message>
<message type="tool">**ALL** 범위:

**원본문서 (2):**
  sources/101/parsed/content.md — 각주에 쓸 문서명 `01-training.md`
  sources/102/parsed/content.md — 각주에 쓸 문서명 `08-compensation.md`

**위키 (2페이지):**
  [목차]
    index.md — 위키 목차
  [복지 제도]
    pages/60c6f9112c3a.md — 학습 및 교육 지원</message>
<message type="ai">
  <content>원본문서를 읽었습니다. 이제 기존 위키 페이지와 index.md를 읽어 겹치는 내용을 확인하겠습니다.</content>
  <tool_call id="toolu_01HTkmeUJ32XoCLNLNLrBWdZ" name="read">{"scope": "ALL", "path": "pages/60c6f9112c3a.md"}</tool_call>
  <tool_call id="toolu_013kkJpetkPXDh9CdMY1Muuw" name="read">{"scope": "ALL", "path": "index.md"}</tool_call>
</message>
<message type="tool">**학습 및 교육 지원**
주소: `pages/60c6f9112c3a.md` | 카테고리: 복지 제도 | 태그: 교육, 도서, 콘퍼런스, 복지, 예산 | 버전: 3
[보기](http://localhost:3000/wiki/ALL/pages/60c6f9112c3a.md)

---

---
title: 학습 및 교육 지원
description: 도서 구매, 연간 교육 예산, 콘퍼런스 참석에 관한 지원 정책
date: 2026-08-02
tags: [교육, 도서, 콘퍼런스, 복지, 예산]
category: 복지 제도
---

모든 구성원이 직무 역량과 관심 분야를 넓힐 수 있도록 도서 구매 지원과 연간 교육 예산을 제공한다. 지원 항목에는 책, 오디오북, 강의, 자격증, 콘퍼런스 참가가 포함된다.[^1]

## 도서 구매 지원

PostHog의 모든 구성원은 직무에 도움이 되는 도서를 자유롭게 구매할 수 있다.[^2]

- 월 **$50** 이하는 별도 승인 없이 구매 가능하다.[^3]
- 도서 예산으로 **오디오북**과 **팟캐스트**도 이용할 수 있다.[^4]
- 도서는 자신의 직무와 직접 관련이 없어도 된다 — 업무에 간접적으로라도 관련되면 충분하다.[^5]
  - 예: 관리자가 리더십 위인전을 읽는 것, 엔지니어가 디자인 서적을 읽는 것
- 매월 열리는 북클럽 **BookHog**의 도서도 예산으로 구매할 수 있다.[^6]

## 연간 교육 예산

직급에 상관없이 모든 구성원에게 연간 교육 예산이 주어진다.[^7]

| 항목 | 내용 |
|---|---|
| 기본 예산 | $1,000 / 연도(역년) |
| 초과 사용 | Brex에서 예산 증액 요청 — 통상 승인됨 |
| 사전 승인 | 불필요 (단, 사전에 매니저와 논의 권장) |
| 사용 대상 | 강의, 교육 프로그램, 공식 자격증, 콘퍼런스 참가 |

&gt; **팁:** 교육 내용을 마친 뒤 팀에 공유하면 조직 전체의 학습에 기여할 수 있다.[^8]

### 비기술직 구성원 대상 프로그래밍 기초 권장

비기술직 구성원도 기초 프로그래밍 과정을 수강하도록 권장한다.[^9] 이는 「당신이 운전자」 문화의 일환으로, 소프트웨어 개발의 기본 개념을 이해하는 것이 목표다. [Codecademy](https://www.codecademy.com/)는 Python, React 등 PostHog가 사용하는 기술을 포함하므로 좋은 시작점이 된다.[^10]

## 콘퍼런스 및 외부 발표

교육 예산은 콘퍼런스 발표, 사용자 그룹 발표, 코칭 활동에도 사용할 수 있다.[^11]

```mermaid
graph LR
    A["교육 예산"] --&gt; B["강의 / 자격증"]
    A --&gt; C["콘퍼런스 참가"]
    A --&gt; D["콘퍼런스 발표 / 코칭"]
    A --&gt; E["도서 / 오디오북"]
    C --&gt; F["팀 결과 공유"]
    B --&gt; F
```

- 콘퍼런스 발표 등 외부 활동은 **월 반일(half day)** 이내가 기본 기준이다.[^12]
- 기준을 넘어야 할 경우 우선 매니저와 협의한다.[^13]

[^1]: 01-training.md, Training — "The better you are at your job, the better PostHog is overall!"
[^2]: 01-training.md, Books — "*Everyone* at PostHog is eligible to buy books to help you in your job."
[^3]: 01-training.md, Books — "spending up to $50/month on books is fine and requires no extra permission."
[^4]: 01-training.md, Books — "You can use your books budget towards audiobooks and podcasts as well, if you prefer."
[^5]: 01-training.md, Books — "Books do not have to be tied directly to your area, and they only need be loosely relevant to your work."
[^6]: 01-training.md, Books — "we host a monthly book club called BookHog, and the budget can be used for those books as well."
[^7]: 01-training.md, Training budget — "We have an annual training budget for every team member, regardless of seniority."
[^8]: 01-training.md, Training budget — "If possible, please share your learnings with the team afterwards!"
[^9]: 01-training.md, Training budget — "We strongly encourage all non-technical team members to take some kind of entry level programming course"
[^10]: 01-training.md, Training budget — "[Codecademy](https://www.codecademy.com/) is a good place to start and they cover many of the technologies we use, such as Python and React."
[^11]: 01-training.md, Conferences — "You can use your training budget for time spent talking at conferences and user groups, including coaching others."
[^12]: 01-training.md, Conferences — "It is expected that you would spend up to half a day a month on these activities."
[^13]: 01-training.md, Conferences — "If you think you need more than that talk to your manager in the first instance."

---
**이 페이지를 참조하는 곳 (1):**
  - index.md (위키 목차) — 링크</message>
<message type="tool">**위키 목차**
주소: `index.md` | 카테고리: 목차 | 태그: 목차, 허브 | 버전: 1
[보기](http://localhost:3000/wiki/ALL/index.md)

---

---
title: 위키 목차
description: ALL 범위 위키의 허브 페이지.
date: 2026-08-02
tags: [목차, 허브]
category: 목차
---

이 페이지는 `ALL` 범위 위키의 입구다. 원본문서를 처리할 때마다 갱신된다.

## 원본문서 현황

| 문서 ID | 문서명 | 반영 페이지 |
|---|---|---|
| 101 | 01-training.md | [학습 및 교육 지원](pages/60c6f9112c3a.md) |

- 원본문서 **1건** 처리 완료 / 위키 페이지 **1건**

## 핵심 내용

### 복지 제도

- [학습 및 교육 지원](pages/60c6f9112c3a.md) — 도서 구매(월 $50 무승인), 연간 교육 예산($1,000), 콘퍼런스 참가·발표 지원 정책

## 최근 변경

| 날짜 | 페이지 | 내용 |
|---|---|---|
| 2026-08-02 | [학습 및 교육 지원](pages/60c6f9112c3a.md) | 신규 생성 — 01-training.md 반영 |</message>
<message type="ai">
  <content>원본문서와 기존 위키를 파악했습니다. `08-compensation.md`는 **보상 체계** 관련 문서로, 기존 위키에는 이 내용이 없습니다. 관련 키워드로 추가 검색해 혹시 겹치는 내용이 있는지 확인합니다.</content>
  <tool_call id="toolu_01Nd69Uh73enks9fqV8PcoU7" name="search">{"scope": "ALL", "mode": "search", "query": "보상 급여 연봉 보수"}</tool_call>
  <tool_call id="toolu_01AT9Lg2hudZJvahak9XYv1A" name="search">{"scope": "ALL", "mode": "search", "query": "주식 스톡옵션 equity 퇴직금"}</tool_call>
</message>
<message type="tool">`보상 급여 연봉 보수`에 해당하는 것이 ALL 범위에 없다.</message>
<message type="tool">**3건** — `주식 스톡옵션 equity 퇴직금`:

**sources/102/parsed/content.md** — 08-compensation.md [보기](http://localhost:3000/wiki/ALL/sources/102/parsed/content.md)
  Probation period
```
Check out our [share options FAQs](/handbook/people/share-options#frequently-asked-questions) to learn more.

### Equity refreshes

Every employee will be eligible for equity refreshes each year you are working at PostHog. These grants are
```

**sources/102/parsed/content.md** — 08-compensation.md [보기](http://localhost:3000/wiki/ALL/sources/102/parsed/content.md)
  Equity
```
You will only hear from them if there has been a change in your pay, not if it is staying the same.

If you recently accepted an offer at PostHog and the benchmark changes between you accepting and joining PostHog, your pay will be re-asses
```

**sources/102/parsed/content.md** — 08-compensation.md [보기](http://localhost:3000/wiki/ALL/sources/102/parsed/content.md)
  Equity &gt; Equity refreshes
```
Whilst the terms of options for _any company_ could vary if we were ever acquired, we have set them up with the following key terms which we believe are industry-leading in their friendliness to employees:

-   Standard 4-year vesting with
```
</message>
<message type="ai">
  <content>기존 위키에는 보상 관련 내용이 전혀 없습니다. `08-compensation.md`는 내용이 방대하므로 주제별로 2~3개 페이지로 나누어 만드는 것이 적합합니다:

1. **보상 체계** — 직급·단계·벤치마크·위치 보정 계수·급여 검토 (핵심 보상 구조)
2. **주식 옵션** — 지분 부여 조건·리프레시·주식옵션 FAQ
3. **계약·급여 지급·수습·퇴직금** — 고용 형태, 급여 지급일, 수습기간, 퇴직금

세 주제가 각각 다른 문서의 내용을 끌어올 수 있을 만큼 독립적이고 분량도 충분합니다. 이제 페이지를 만들겠습니다.</content>
  <tool_call id="toolu_01FRwRGqnE2Npu5bsVPDbrgc" name="create">{"scope": "ALL", "title": "보상 체계", "category": "보상 체계", "tags": ["보상", "급여", "직급", "단계", "위치보정", "급여검토"], "content": "---\ntitle: 보상 체계\ndescription: PostHog의 직급·단계·벤치마크·위치 보정 계수 및 정기 급여 검토 방식\ndate: 2026-08-02\ntags: [보상, 급여, 직급, 단계, 위치보정, 급여검토]\ncategory: 보상 체계\n---\n\nPostHog는 투명하고 체계적인 보상 시스템을 운영한다. 직급(Level)·단계(Step)·벤치마크·위치 보정 계수를 조합해 급여를 산정하며, 연간 2~3회 정기 검토를 통해 구성원이 별도 요청 없이도 적정 수준을 유지하도록 한다.[^1]\n\n## 직급 (Level)\n\n직급은 직책이 아니라 PostHog 내 **영향 범위**를 나타낸다.[^2] 직급이 높다고 중요도가 높아지는 것은 아니며, 모든 구성원이 회사의 주인처럼 느끼도록 방대한 계층 구조를 두지 않는다.\n\n| 직급 | 설명 |\n|---|---|\n| Junior | 소규모 또는 특정 직무 범위의 프로젝트에 기여 (채용 빈도 낮음) |\n| Intermediate | 중소 규모 영향의 프로젝트를 소유 |\n| Senior | 고영향 프로젝트 또는 제품 전체를 소유·주도하며, 필요한 모든 것을 직접 실행 |\n| Staff | Senior와 동일하되, 팀·제품·직군을 넘어 회사 전체에 영향을 주는 대형 프로젝트를 **일관되게** 소유·주도 |\n| Director | 최고 수준에서 운영하며, 통상 Blitzscale 팀의 일원이자 매니저 오브 매니저 |\n| Principal | Director와 동급 — Staff와 동일하나 극히 드문 스킬을 보유하거나 매우 높은 수준에서 운영 |\n\n직급은 체크리스트가 아니며, 기술적(indicative) 설명일 뿐이다. Blitzscale 팀이 PostHog 내 다른 구성원들과 비교해 판단한다.[^3]\n\n## 단계 (Step)\n\n각 직급 안에서 세분화된 단계를 두어 유연성을 높인다.[^4]\n\n| 단계 | 기준 |\n|---|---|\n| Learning | 기대 수준을 맞춰가는 중 |\n| Established | 기대 수준에 부합 |\n| Thriving | 기대 수준 초과 |\n| Expert | 기대 수준을 **일관되게** 초과 |\n\n입사 초년생이나 해당 직무 첫 경험자를 제외하면 기본적으로 **Established** 단계로 채용한다.[^5] 이를 통해 성공적인 출발을 보장하고, 직급 상향 없이도 급여 인상 여지를 남긴다.\n\n급여 검토 시 동일 직무의 동료와 비교해 단계 상향 여부를 결정한다. 단계나 직급 이동 방법은 [경력 개발 핸드북](https://posthog.com/handbook/people/career-progression)에서 확인할 수 있다.[^6]\n\n## 벤치마크\n\n모든 역할의 급여 기준은 **샌프란시스코 시장 기준**이다.[^7]\n\n- 주요 데이터 출처: [Pave](https://www.pave.com/marketdata)[^8]\n- 엔지니어링 직군: 시장 상위 **90th percentile** 지급[^9]\n- 기타 직군: **50th percentile + 20%** 기준[^10]\n\n임원 직군은 별도 데이터베이스를 사용하며 평균 이상이나 시장 최상위는 아니다. 임원은 역할별 단 한 명만 존재하므로, 내부 등가성을 위한 복잡한 시스템을 구축하는 것은 비합리적이기 때문이다.[^11]\n\n## 위치 보정 계수 (Location Factor)\n\n대부분의 위치 보정 계수는 GitLab의 계수를 기반으로 한다. **생활비가 아닌 해당 위치의 시장 채용 비용**을 기준으로 산정한다. 따라서 샌프란시스코의 계수가 가장 높다.[^12]\n\n```mermaid\ngraph LR\n    A[\"SF 기준 벤치마크\"] --&gt; B[\"× 위치 보정 계수\"]\n    B --&gt; C[\"실 지급 급여 (현지 통화)\"]\n    D[\"미국 최소 계수: 0.8\"] --&gt; B\n    E[\"해외 최소 계수: 0.6\"] --&gt; B\n```\n\n- 미국 내 최소 계수 **0.8**, 해외 최소 계수 **0.6** — 생활비가 매우 낮은 국가·지역에서 발생하는 극단적 격차를 방지한다.[^13]\n- 위치 계수는 현지 환율을 반영하므로, 환율 변동 시마다 별도 업데이트가 필요 없다.[^14]\n- 급여는 **항상 현지 통화**로 지급된다. 현지에서 USD 거래가 일반적인 경우는 예외다.[^15]\n- GitLab은 ERI, Numbeo, Comptryx, Radford, Robert Half, Dice의 데이터를 조합해 위치 계수를 산출한다.[^16]\n\n### 이동(Relocation) 시 처리\n\n- 이동 시 급여가 인상 또는 인하될 수 있으며, **다음 급여 검토 시 조정**된다.[^17]\n- 인상이 되는 경우라면 **사전 승인이 필요**하다 — 예산 상 항상 가능하지는 않다.[^18]\n- 유목민(Nomad) 생활을 하는 경우, 향후 6개월 중 가장 오래 머무는 곳을 기준으로 계수를 설정한다.[^19]\n\n## 정기 급여 검토\n\nPostHog는 연간 **2~3회** 급여를 검토하며, 매 검토 시 모든 구성원을 대상으로 한다.[^20] 구성원이 별도로 요청할 필요가 없다.\n\n```mermaid\ngraph TD\n    A[\"급여 검토 시작 (연 2~3회)\"] --&gt; B[\"전 구성원 대상\"]\n    B --&gt; C{\"단계·직급 변경?\"}\n    C --&gt;|\"예\"| D[\"급여 인상 + 변경 통보\"]\n    C --&gt;|\"아니오\"| E{\"벤치마크 상승?\"}\n    E --&gt;|\"예\"| F[\"급여 인상 (단계 유지 가능)\"]\n    E --&gt;|\"아니오\"| G[\"변동 없음 — 통보 없음\"]\n```\n\n- 급여 검토 요청 빈도는 인상 여부에 영향을 주지 않는다 — 소외 집단의 협상 격차를 줄이기 위한 장치다.[^21]\n- 인플레이션은 벤치마크 데이터에 이미 반영되어 있으므로, 별도 인플레이션 인상은 없다.[^22]\n- 변경이 있을 때만 관련 Blitzscale 팀원이 통보한다.[^23]\n- 입사 수락 후 벤치마크가 바뀌면, 다음 급여 검토 시 재평가한다.[^24]\n- 역할이 바뀌어도 급여 변경은 **다음 가장 가까운 검토** 시점에 처리된다.[^25]\n- 보상 관련 문의는 매니저가 아닌 **관련 임원**에게 한다.[^26]\n\n&gt; **관련 페이지:** 주식 옵션에 대한 세부 사항은 [주식 옵션](pages/PLACEHOLDER_EQUITY.md) 참고. 수습기간·퇴직금·계약 형태는 [계약·급여 지급·수습·퇴직금](pages/PLACEHOLDER_CONTRACT.md) 참고.\n\n[^1]: 08-compensation.md, How it works — \"We have a set system for compensation as part of being transparent.\"\n[^2]: 08-compensation.md, Level — \"Level does not correlate with increased importance, but with impact within PostHog.\"\n[^3]: 08-compensation.md, Level — \"It's important to note that this is not a checklist. These descriptions are indicative, and there will always be a degree of judgement by the Blitzscale team to decide which level you're at, also based on other people within PostHog.\"\n[^4]: 08-compensation.md, Step — \"Within each level, we believe there's a place to have incremental steps to allow for more flexibility.\"\n[^5]: 08-compensation.md, Step — \"we hire into the *Established* step by default. This will give everyone the opportunity to be set up for success and leave enough room for salary increases, without the need to move up in seniority.\"\n[^6]: 08-compensation.md, Step — \"Here's how to move from one step or level to another\"\n[^7]: 08-compensation.md, Benchmark — \"the benchmark for each role we are hiring for is based on the market rate in San Francisco.\"\n[^8]: 08-compensation.md, Benchmark — \"We use Pave as our main source for our salary benchmark\"\n[^9]: 08-compensation.md, Benchmark — \"we pay near the top of market, which we define as being the 90th percentile, at the time of review.\"\n[^10]: 08-compensation.md, Benchmark — \"For other roles we still try to pay towards the top of market, which we define as 50th percentile + 20%.\"\n[^11]: 08-compensation.md, Executive compensation — \"As a rule, executives are paid above average but not top of market. The reason for less sophistication here is that we have very few executives, and only one for each role by definition.\"\n[^12]: 08-compensation.md, Location factor — \"Location factors are based on *cost of market*, not cost of living. This means that we look at how much it typically costs to hire a person in that role in that location, not how much it costs to live there. This is why, for example, our location factor for San Francisco is the highest\"\n[^13]: 08-compensation.md, Location factor — \"We set a floor of 0.8 in the US, and 0.6 everywhere else, to avoid creating huge disparities in pay if someone happens to live in an exceptionally low cost of living country/state.\"\n[^14]: 08-compensation.md, Location factor — \"The location factor takes your local exchange rate into account, so we don't have to keep updating exchange rates when they fluctuate.\"\n[^15]: 08-compensation.md, Location factor — \"You will always be paid in your local currency, unless there is a very good reason not to (e.g. it is normal in your country to transact in USD).\"\n[^16]: 08-compensation.md, Location factor — \"GitLab uses a combination of data from Economic Research Institute (ERI), Numbeo, Comptryx, Radford, Robert Half, and Dice to calculate what a fair market rate is for each location.\"\n[^17]: 08-compensation.md, Relocating — \"If you're planning on relocating, your salary may be adjusted (up or down) to your new location. This will be done at the next compensation review.\"\n[^18]: 08-compensation.md, Relocating — \"If this represents an increase in pay, *we need to approve this change in advance* - we cannot guarantee it is always possible, as our budgets may not allow it.\"\n[^19]: 08-compensation.md, Relocating — \"If you are nomading, we will set your location factor for the place that you are spending the most time in over the next 6 months.\"\n[^20]: 08-compensation.md, Pay reviews — \"We review pay proactively 2-3 times a year, and everybody on the team is reviewed at every pay review.\"\n[^21]: 08-compensation.md, How the review process works — \"To make sure everyone has an equal chance at getting a pay rise, we do not factor in how frequently someone requests one... as underrepresented groups are statistically less likely to request a pay rise.\"\n[^22]: 08-compensation.md, Pay reviews — \"When we review pay we don't take inflation into account, as this is already accounted for by market data.\"\n[^23]: 08-compensation.md, How the review process works — \"You will only hear from them if there has been a change in your pay, not if it is staying the same.\"\n[^24]: 08-compensation.md, How the review process works — \"If you recently accepted an offer at PostHog and the benchmark changes between you accepting and joining PostHog, your pay will be re-assessed during the next pay review.\"\n[^25]: 08-compensation.md, How the review process works — \"if you change role for example, any changes will usually happen at the next closest review.\"\n[^26]: 08-compensation.md, How the review process works — \"If you need to talk to someone about your compensation or how the calculator works, you should ask the relevant member of the exec team in the first instance.\"\n"}</tool_call>
</message>
<message type="tool">**보상 체계** 생성 — `pages/6544a7daa7ca.md`
카테고리: 보상 체계 | 태그: 보상, 급여, 직급, 단계, 위치보정, 급여검토
[보기](http://localhost:3000/wiki/ALL/pages/6544a7daa7ca.md)

각주에 근거를 단다: `[^1]: 인사규정.pdf, 3장 휴가 — "원문 문장"`</message>
<message type="ai">
  <content>보상 체계 페이지가 만들어졌습니다. 이제 주식 옵션 페이지를 만들겠습니다.</content>
  <tool_call id="toolu_01FvL5LxZSNEqktVDz12HiwR" name="create">{"scope": "ALL", "title": "주식 옵션", "category": "보상 체계", "tags": ["주식옵션", "지분", "equity", "베스팅", "보상"], "content": "---\ntitle: 주식 옵션\ndescription: PostHog 구성원 주식 옵션 부여 조건, 베스팅 일정, 연간 리프레시 정책\ndate: 2026-08-02\ntags: [주식옵션, 지분, equity, 베스팅, 보상]\ncategory: 보상 체계\n---\n\nPostHog는 모든 구성원이 회사의 성장에 함께 투자할 수 있도록 주식 옵션(Share Options)을 지급한다.[^1] 역할·직급·위치를 기반으로 부여하며, 평균 수준의 지분이지만 직원 친화적인 조건을 업계 최고 수준으로 설계했다.[^2]\n\n## 기본 옵션 조건\n\n| 항목 | 내용 |\n|---|---|\n| 베스팅 기간 | 4년 (1년 클리프) |\n| 퇴사 후 행사 기간 | **10년** |\n| 이중 트리거 가속 | 인수로 인해 해고·강제 퇴직 시 전체 옵션 즉시 부여 |\n| 베스팅 시작일 | 입사일 (수습기간 종료 후 아님) |\n| UK 구성원 | EMI 또는 CSOP 세제 혜택 적용 |\n\n- 이중 트리거 가속(double trigger acceleration)이란, 회사가 인수되어 해고되거나 강제 퇴직 당하는 경우 즉시 모든 옵션을 수령하는 조건이다.[^3]\n- 영국 거주 구성원의 경우 [EMI 주식 옵션 제도](https://www.gov.uk/tax-employee-share-schemes/enterprise-management-incentives-emis) 또는 [CSOP 제도](https://www.gov.uk/tax-employee-share-schemes/company-share-option-plan)를 통해 세제 혜택을 받을 수 있다.[^4]\n\n## 옵션 승인 및 행사가 안내\n\n옵션 승인에는 이사회 회의와 기업 평가가 필요하므로 시간이 걸릴 수 있다.[^5]\n\n```mermaid\ngraph LR\n    A[\"입사\"] --&gt; B[\"베스팅 시작 (입사일)\"]\n    B --&gt; C[\"1년 클리프 도달\"]\n    C --&gt; D[\"이후 월할 베스팅 (3년)\"]\n    D --&gt; E[\"4년 완전 베스팅\"]\n    F[\"이사회 승인\"] --&gt; G[\"Carta 이메일 수신\"]\n```\n\n- 베스팅은 옵션 계약서 수령일이 아닌 **입사일**부터 시작한다.[^6]\n- 특정 수량은 약속할 수 있으나, 행사가(strike price)는 제3자가 수행하는 기업 평가에 따라 달라지므로 사전 확정이 불가하다.[^7]\n- 옵션 승인은 이사회에서 분기별로 이루어지며, 펀딩 라운드가 진행 중일 경우 승인이 지연될 수 있다.[^8]\n\n## 연간 주식 옵션 리프레시\n\n매년 근속하는 모든 구성원은 주식 옵션 리프레시를 받을 수 있다.[^9]\n\n| 항목 | 내용 |\n|---|---|\n| 리프레시 규모 | 현재 역할 신규 부여 기준의 **18%~25%** |\n| 비율 결정 요소 | 개인 성과 (연도별 변동) |\n| 결정 시점 | 급여 검토 사이클 |\n| 통보 방법 | 관련 #team-blitzscale 멤버 통보 |\n| 클리프 | 12개월 클리프 (원 부여와 동일 조건) |\n| 베스팅 기준일 | 실제 기념일(anniversary date)로 소급 |\n\n- 리프레시 옵션은 기업 가치 변동으로 인해 원 부여와 다른 행사가가 적용될 수 있다.[^10]\n- 펀딩 라운드 중에는 신규 옵션 발행이 제한되어 승인이 지연될 수 있다.[^11]\n\n## 자주 묻는 질문\n\n주식 옵션에 관한 상세 FAQ는 [share options FAQ](https://posthog.com/handbook/people/share-options#frequently-asked-questions)에서 확인할 수 있다.[^12]\n\n&gt; **관련 페이지:** 급여 구조 및 직급·단계 정의는 [보상 체계](pages/6544a7daa7ca.md) 참고.\n\n[^1]: 08-compensation.md, Equity — \"It's important to us that all PostHog employees can feel invested in the company's success. Every one of us plays a critical role in the business and deserves a share in the company's success as we grow.\"\n[^2]: 08-compensation.md, Equity — \"Our general philosophy here is average equity, with extremely employee-friendly terms and options for liquidity through secondary.\"\n[^3]: 08-compensation.md, Equity — \"Double trigger acceleration, which means if you are let go or forced to leave due to the company being acquired, you receive all of your options at that time\"\n[^4]: 08-compensation.md, Equity — \"For UK-based team members, eligible options are part of the EMI share options scheme or the CSOP scheme, which are tax-advantaged\"\n[^5]: 08-compensation.md, Equity — \"It can take time to approve options, as it requires a board meeting and company valuation.\"\n[^6]: 08-compensation.md, Equity — \"Vesting will always start from when you joined PostHog, not from when you receive your option agreement.\"\n[^7]: 08-compensation.md, Equity — \"While we can commit to a particular number, we cannot commit to a particular strike price when offering share options, as the valuations are done by a third party\"\n[^8]: 08-compensation.md, Equity refreshes — \"Grants are approved quarterly by the board, though vesting is back-dated begins on the actual anniversary date... Funding rounds disrupt when we are able to issue new grants, so approvals may be delayed if we are actively fundraising.\"\n[^9]: 08-compensation.md, Equity refreshes — \"Every employee will be eligible for equity refreshes each year you are working at PostHog.\"\n[^10]: 08-compensation.md, Equity refreshes — \"These refresher grants will be on the same terms as your original grant with a 12 month cliff, they will likely be subject to a different strike price due to changes in valuations.\"\n[^11]: 08-compensation.md, Equity refreshes — \"Funding rounds disrupt when we are able to issue new grants, so approvals may be delayed if we are actively fundraising.\"\n[^12]: 08-compensation.md, Equity — \"Check out our share options FAQs to learn more.\"\n"}</tool_call>
</message>
<message type="tool">**주식 옵션** 생성 — `pages/1500fc2731c7.md`
카테고리: 보상 체계 | 태그: 주식옵션, 지분, equity, 베스팅, 보상
[보기](http://localhost:3000/wiki/ALL/pages/1500fc2731c7.md)

각주에 근거를 단다: `[^1]: 인사규정.pdf, 3장 휴가 — "원문 문장"`</message>
<message type="ai">
  <content>이제 계약·급여 지급·수습·퇴직금 페이지를 만들겠습니다.</content>
  <tool_call id="toolu_01RTRx1CBh23JQVSq9sMFxYB" name="create">{"scope": "ALL", "title": "계약·급여 지급·수습·퇴직금", "category": "보상 체계", "tags": ["계약", "급여지급", "수습기간", "퇴직금", "고용형태"], "content": "---\ntitle: 계약·급여 지급·수습·퇴직금\ndescription: PostHog의 고용 계약 형태, 급여 지급 주기, 수습기간 및 퇴직금 정책\ndate: 2026-08-02\ntags: [계약, 급여지급, 수습기간, 퇴직금, 고용형태]\ncategory: 보상 체계\n---\n\nPostHog의 고용 형태, 급여 지급 일정, 수습기간, 그리고 퇴직 시 적용되는 퇴직금 정책을 정리한다.\n\n## 고용 계약 형태\n\nPostHog는 세 개 지역에 법인을 두고 있으며, 해당 국가 구성원은 직접 고용한다.[^1]\n\n| 지역 | 고용 형태 |\n|---|---|\n| 미국 | PostHog 또는 자회사에 직접 고용 |\n| 영국 | PostHog 또는 자회사에 직접 고용 |\n| 독일 | PostHog 또는 자회사에 직접 고용 |\n| 기타 국가 | [Deel](https://app.letsdeel.com/)을 통한 국제 고용 대행(EOR) |\n\n- 미국·영국·독일 이외 거주자는 Deel이 공식 고용주 역할을 하며, 이는 구성원의 권리나 복지에 영향을 주지 않는다.[^2]\n- 일부 경우 독립 계약직(independent contractor)으로 Deel을 통해 월별 인보이스를 발행하며, 이 경우 세금은 본인이 책임진다.[^3]\n- Deel은 거의 모든 국가와 통화를 지원한다.[^4]\n\n## 급여 지급 일정\n\n```mermaid\ngraph LR\n    A[\"영국 / 국제 계약직\"] --&gt; B[\"매월 마지막 영업일 또는 이전\"]\n    C[\"미국\"] --&gt; D[\"15일 및 말일 월 2회\"]\n    E[\"Deel 계약\"] --&gt; F[\"매월 마지막 영업일\"]\n```\n\n| 지역 | 지급 주기 |\n|---|---|\n| 영국 | 월 1회 — 해당 월 마지막 영업일 또는 이전 |\n| 국제 계약직 | 월 1회 — 해당 월 마지막 영업일 또는 이전 |\n| 미국 | 월 2회 — 15일, 말일 |\n| Deel (EOR) | 월 1회 — 마지막 영업일 |\n\n[^5]\n\n## 수습기간\n\n### 일반 수습기간 (3개월)\n\n입사 후 첫 3개월이 수습기간이다.[^6]\n\n- **구성원이 종료를 원하는 경우:** 1주일 사전 통보\n- **PostHog가 종료를 결정하는 경우:** 기본급 4주치 지급, 통상 당일 퇴직 처리\n\n### 연장 수습기간 (6개월)\n\n아래 두 경우는 6개월 수습기간이 적용된다.[^7]\n\n| 대상 | 이유 |\n|---|---|\n| 영업직 (예: Account Executive) | 계약 체결 여부를 3개월 내에 판단하기 어려운 영업 사이클 특성 |\n| 독일 거주 구성원 | 현지 시장 관행 및 고용 관계 종료의 운영적 어려움을 고려 |\n\n독일 구성원의 경우 수습 중 어느 쪽이든 **1개월 사전 통보**로 계약을 종료할 수 있다.[^8]\n\n### 수습기간 중 관리\n\n매니저는 수습기간 전반에 걸쳐 성과를 모니터링하고 검토한다.[^9] 성과 우려 사항이 있거나 장기 적합성에 대한 의문이 있으면 즉시 구성원과 논의해야 한다.[^10]\n\n수습기간 종료 시 별도의 합격 통보는 없다 — 30·60·90일 체크인을 통해 이미 명확히 파악한 것이 기본이다.[^11]\n\n## 퇴직금\n\nPostHog에서는 평균적인 성과에도 후한 퇴직금을 제공한다.[^12]\n\n| 구분 | 내용 |\n|---|---|\n| 적용 시점 | 수습기간 종료 후 (일반 3개월, 영업직 6개월) |\n| 퇴직금 | 기본급 **총 4개월** (법적 의무 기간 포함) |\n| 조건 | 표준 퇴직 후 증명서 또는 면책 동의서 서명 |\n| 독일 구성원 | 6개월 수습 완료 후 현지 법령에 따름 |\n\n- 즉시 업무 중단 후 급여 지급으로 대체하거나 \"가든 리브(garden leave)\"로 처리할 수 있다.[^13]\n- **자발적 퇴직:** 통상 1개월 사전 통보 (국가 법률이나 계약 조건에 따라 다를 수 있다).[^14]\n- 커미션·보너스 요소가 있는 역할은 퇴직일 기준 발생분을 지급한다.[^15]\n- 중대한 비위(gross misconduct)로 계약이 종료되는 경우에는 사전 통보 없이 해고될 수 있다.[^16]\n- 이 정책이 현지 법과 충돌할 경우 현지 법률이 우선한다.[^17]\n\n&gt; **관련 페이지:** 급여 산정 구조는 [보상 체계](pages/6544a7daa7ca.md) 참고. 주식 옵션 조건은 [주식 옵션](pages/1500fc2731c7.md) 참고.\n\n[^1]: 08-compensation.md, Contracts — \"We currently operate our employment contracts in the three geographic regions where we have business entities: United States of America, United Kingdom, Germany\"\n[^2]: 08-compensation.md, Contracts — \"If you live outside the US, the UK or Germany, we use Deel as our international employer of record. This means you are technically employed by Deel on our behalf. This doesn't affect your rights or benefits.\"\n[^3]: 08-compensation.md, Contracts — \"In some cases, you may be an independent contractor, in which case you will invoice us monthly via Deel. As a contractor, you will be responsible for your own taxes.\"\n[^4]: 08-compensation.md, Contracts — \"Deel offers pretty much all countries and currencies.\"\n[^5]: 08-compensation.md, Payroll — \"In the UK and for international contractors, we run payroll monthly, on or before the last working day of the month. In the US, we run payroll twice a month, on the 15th and on the last day of the month. Deel runs payroll on the last working day of the month.\"\n[^6]: 08-compensation.md, Probation period — \"the first 3 months of your employment with PostHog is a probation period. During this time, you can choose to end your contract with 1 week's notice. If we choose to end your contract, PostHog will pay you 4 weeks' base salary pay, but usually ask you to finish on the same day.\"\n[^7]: 08-compensation.md, Probation period — \"People in sales roles, such as Account Executives, have a 6 month probation period... German employees also have a 6 month probation period\"\n[^8]: 08-compensation.md, Probation period — \"During probation, either PostHog or the German employee may choose to end the employment contract with 1 month notice.\"\n[^9]: 08-compensation.md, Probation period — \"Your manager is responsible for monitoring and specifically reviewing your performance throughout this initial period.\"\n[^10]: 08-compensation.md, Probation period — \"If under-performance is a concern, or if there is any hesitation regarding the future at PostHog, this should be discussed immediately with you and your manager.\"\n[^11]: 08-compensation.md, Probation period — \"At the end of your probation period, you won't usually receive formal confirmation that you've passed probation, the default is no communication. By that point, you should already have a clear understanding of your performance and progress through your 30/60/90-day check-ins with your manager.\"\n[^12]: 08-compensation.md, Severance — \"At PostHog, average performance gets a generous severance.\"\n[^13]: 08-compensation.md, Severance — \"In some cases, we might ask you to stop working right away and pay you instead of having you work through your notice period, or set up a 'garden leave' depending on what is most appropriate for your location and contract.\"\n[^14]: 08-compensation.md, Severance — \"If the decision to leave is yours, then we generally just require 1 month of notice, though this can vary depending on your country's laws or the specifics of your contract.\"\n[^15]: 08-compensation.md, Severance — \"If you are in a role with a commission/bonus component, you will be paid the amount you are owed as of your last day at PostHog.\"\n[^16]: 08-compensation.md, Severance — \"if your contract is terminated due to gross misconduct then you may be dismissed without notice.\"\n[^17]: 08-compensation.md, Severance — \"If this policy conflicts with the requirements of your local jurisdiction, then those local laws will take priority.\"\n"}</tool_call>
</message>
<message type="tool">**계약·급여 지급·수습·퇴직금** 생성 — `pages/a9e11baa2bd0.md`
카테고리: 보상 체계 | 태그: 계약, 급여지급, 수습기간, 퇴직금, 고용형태
[보기](http://localhost:3000/wiki/ALL/pages/a9e11baa2bd0.md)

각주에 근거를 단다: `[^1]: 인사규정.pdf, 3장 휴가 — "원문 문장"`</message>
<message type="ai">
  <content>3개 페이지 모두 생성됐습니다. 이제 보상 체계 페이지의 플레이스홀더 링크를 실제 주소로 교체하고, index.md를 갱신하겠습니다.</content>
  <tool_call id="toolu_01AtfDP27PK3d5VZtkgZeHbz" name="edit">{"scope": "ALL", "path": "pages/6544a7daa7ca.md", "old_text": "&gt; **관련 페이지:** 주식 옵션에 대한 세부 사항은 [주식 옵션](pages/PLACEHOLDER_EQUITY.md) 참고. 수습기간·퇴직금·계약 형태는 [계약·급여 지급·수습·퇴직금](pages/PLACEHOLDER_CONTRACT.md) 참고.", "new_text": "&gt; **관련 페이지:** 주식 옵션에 대한 세부 사항은 [주식 옵션](pages/1500fc2731c7.md) 참고. 수습기간·퇴직금·계약 형태는 [계약·급여 지급·수습·퇴직금](pages/a9e11baa2bd0.md) 참고."}</tool_call>
</message>
<message type="tool">**보상 체계** 수정 — `pages/6544a7daa7ca.md`
카테고리: 보상 체계 | 태그: 보상, 급여, 직급, 단계, 위치보정, 급여검토
[보기](http://localhost:3000/wiki/ALL/pages/6544a7daa7ca.md)

각주에 근거를 단다: `[^1]: 인사규정.pdf, 3장 휴가 — "원문 문장"`
**이 페이지를 참조하는 페이지 2건** — 같이 손볼지 확인:
  - `pages/1500fc2731c7.md` (주식 옵션) — 링크
  - `pages/a9e11baa2bd0.md` (계약·급여 지급·수습·퇴직금) — 링크

**수정 후 문맥:**
```
...- 변경이 있을 때만 관련 Blitzscale 팀원이 통보한다.[^23]
- 입사 수락 후 벤치마크가 바뀌면, 다음 급여 검토 시 재평가한다.[^24]
- 역할이 바뀌어도 급여 변경은 **다음 가장 가까운 검토** 시점에 처리된다.[^25]
- 보상 관련 문의는 매니저가 아닌 **관련 임원**에게 한다.[^26]

&gt; **관련 페이지:** 주식 옵션에 대한 세부 사항은 [주식 옵션](pages/1500fc2731c7.md) 참고. 수습기간·퇴직금·계약 형태는 [계약·급여 지급·수습·퇴직금](pages/a9e11baa2bd0.md) 참고.

[^1]: 08-compensation.md, How it works — "We have a set system for compensation as part of being transparent."
[^2]: 08-compensation.md, Level — "Level does not correlate with increased importance, but with impact within PostHog."
[^3]: 08-compensation.md, Level — "It's important to note that this is not a checklist. These descriptions are indicative, and there will always be a degree of judgement by the Blitzscale team to decide which level you're at, also based on other people within PostHog."
[^4]: 08-compensation.md, Step — "Within each level, we believe there's a place to have incremental steps to allow for more flexibility."...
```</message>

