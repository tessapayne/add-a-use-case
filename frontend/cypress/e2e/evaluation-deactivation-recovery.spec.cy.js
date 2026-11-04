// UC-STU-deactivate-student T-recipient/T-edit/T-recover; UC-EVA-submit-evaluation 5a.
const ok = (data) => ({ flag: true, code: 200, message: 'Success', data })
const roster = [
  { id: 4, firstName: 'John', lastName: 'Smith', enabled: true },
  { id: 5, firstName: 'Eric', lastName: 'Hudson', enabled: true },
  { id: 6, firstName: 'Jerry', lastName: 'Moon', enabled: false }
]
const row = (id, recipient, comment = 'saved') => ({
  evaluationId: id,
  week: '2023-W32',
  evaluatorId: 4,
  evaluateeId: recipient,
  evaluateeName: roster.find((s) => s.id === recipient).firstName,
  ratings: [{ criterionId: 1, criterion: 'Quality', actualScore: 8 }],
  publicComment: comment,
  privateComment: ''
})
function visit(rows = [], members = roster, initialFailure) {
  cy.clock(Date.UTC(2023, 7, 20, 16), ['Date'])
  let sectionCalls = 0
  let rubricCalls = 0
  cy.intercept('GET', '**/sections/2', (req) => {
    if (initialFailure === 'section' && ++sectionCalls === 1)
      req.reply({
        statusCode: 500,
        body: { flag: false, code: 500, message: 'Section unavailable' }
      })
    else req.reply(ok({ rubricId: 1 }))
  }).as('section')
  cy.intercept('GET', '**/rubrics/1', (req) => {
    if (initialFailure === 'rubric' && ++rubricCalls === 1)
      req.reply({
        statusCode: 500,
        body: { flag: false, code: 500, message: 'Rubric unavailable' }
      })
    else
      req.reply({
        delay: initialFailure ? 800 : 0,
        body: ok({
          criteria: [{ criterionId: 1, criterion: 'Quality', description: 'Quality', maxScore: 10 }]
        })
      })
  }).as('rubric')
  cy.intercept('GET', '**/students/teams/1', ok(members)).as('roster')
  cy.intercept('GET', '**/evaluations/evaluators/4/week/*', ok(rows)).as('stored')
  cy.intercept('POST', '**/evaluations/weeks/*/receipt', ok(null)).as('receipt')
  cy.request({
    method: 'POST',
    url: 'http://localhost/api/v1/users/login',
    auth: { username: 'j.smith@abc.edu', password: '123456' }
  }).then(({ body }) => {
    cy.visit('/evaluations/submit-evaluations', {
      onBeforeLoad(win) {
        win.localStorage.setItem('token', JSON.stringify({ token: body.data.token }))
        win.localStorage.setItem('userInfo', JSON.stringify({ userInfo: body.data.userInfo }))
      }
    })
  })
  cy.wait(initialFailure ? '@' + initialFailure : '@stored')
}

describe('Evaluation recipient lifecycle and independent outcomes', () => {
  for (const stage of ['section', 'rubric']) {
    it(`retries incomplete ${stage} initialization before enabling submission (review R2)`, () => {
      visit([], roster, stage)
      cy.get('[data-cy=evaluation-recovery]').should('contain', 'could not be loaded')
      cy.get('[data-cy=evaluation-submit]').should('be.disabled')
      cy.get('@stored.all').should('have.length', 0)
      cy.get('@roster.all').should('have.length', 0)
      cy.get('[data-cy=evaluation-reload]').click()
      cy.get('[data-cy=evaluation-submit]').should('be.disabled')
      cy.wait('@stored')
      cy.get('[data-cy=evaluation-submit]').should('be.enabled')
      cy.get('.el-table__body tr').should('have.length', 2)
      cy.get('.el-table__body tr').first().find('input').should('have.value', '10')
      cy.intercept('POST', '**/evaluations', (req) =>
        req.reply(ok({ ...req.body, evaluationId: 100 + req.body.evaluateeId }))
      ).as('initializedCreate')
      cy.get('[data-cy=evaluation-submit]').click()
      cy.wait('@initializedCreate')
        .its('request.body.ratings')
        .should('deep.equal', [{ criterionId: 1, criterion: 'Quality', actualScore: 10 }])
      cy.wait('@receipt')
    })
  }

  it('offers active new recipients and preserves saved inactive-recipient edits (T-recipient/T-edit)', () => {
    visit([row(10, 6)])
    cy.get('.el-table__body')
      .should('contain', 'John')
      .and('contain', 'Eric')
      .and('contain', 'Jerry')
    cy.intercept('PUT', '**/evaluations/10', (req) =>
      req.reply(ok({ ...req.body, evaluationId: 10 }))
    ).as('edit')
    cy.intercept('POST', '**/evaluations', (req) =>
      req.reply(ok({ ...req.body, evaluationId: 100 + req.body.evaluateeId }))
    ).as('create')
    cy.get('[data-cy=evaluation-submit]').click()
    cy.wait('@edit')
    cy.get('@create.all').should('have.length', 2)
    cy.wait('@receipt')
    cy.get('@edit.all').should('have.length', 1)
  })

  it('never constructs a new inactive-recipient row (T-recipient)', () => {
    visit()
    cy.get('.el-table__body')
      .should('contain', 'John')
      .and('contain', 'Eric')
      .and('not.contain', 'Jerry')
  })

  it('reconciles mixed saved/rejected/unknown rows, retains active drafts and retries known saves with PUT (T-recover)', () => {
    visit(
      [],
      roster.map((s) => ({ ...s, enabled: true }))
    )
    cy.get('.el-table__body tr').eq(0).find('textarea').first().type('my active draft')
    cy.intercept('POST', '**/evaluations', (req) => {
      if (req.body.evaluateeId === 4) req.reply(ok({ ...req.body, evaluationId: 14 }))
      else if (req.body.evaluateeId === 5)
        req.reply({
          statusCode: 502,
          body: { flag: false, code: 502, message: 'Gateway lost the upstream result' }
        })
      else
        req.reply({
          statusCode: 400,
          body: { flag: false, code: 400, message: 'This student is inactive' }
        })
    }).as('create')
    cy.intercept('GET', '**/students/teams/1', ok(roster))
    cy.intercept('GET', '**/evaluations/evaluators/4/week/*', ok([row(14, 4), row(15, 5)]))
    cy.get('[data-cy=evaluation-submit]').click()
    cy.get('[data-cy=evaluation-recovery]').should('contain', 'unsaved evaluation(s) removed')
    cy.get('[data-cy=evaluation-submit]').should('be.enabled')
    cy.get('.el-table__body tr').should('have.length', 2)
    cy.get('.el-table__body tr')
      .first()
      .find('textarea')
      .first()
      .should('have.value', 'my active draft')
    cy.get('@receipt.all').should('have.length', 0)
    cy.contains('Peer evaluations saved successfully').should('not.exist')
    cy.intercept('PUT', '**/evaluations/*', (req) => req.reply(ok(req.body))).as('edit')
    cy.get('[data-cy=evaluation-submit]').click()
    cy.get('@edit.all').should('have.length', 2)
    cy.get('@create.all').should('have.length', 3)
    cy.wait('@receipt')
  })

  it('retains drafts and blocks blind replay when recovery reads fail (T-recover)', () => {
    visit()
    cy.get('.el-table__body tr').first().find('textarea').first().type('recover this draft')
    cy.intercept('POST', '**/evaluations', {
      statusCode: 502,
      body: { flag: false, code: 502, message: 'Gateway lost the upstream result' }
    }).as('unknown')
    cy.intercept('GET', '**/students/teams/1', {
      statusCode: 500,
      body: { flag: false, code: 500, message: 'Read failed' }
    })
    cy.get('[data-cy=evaluation-submit]').click()
    cy.get('[data-cy=evaluation-recovery]').should('contain', 'drafts are retained')
    cy.get('[data-cy=evaluation-submit]').should('be.disabled')
    cy.get('.el-table__body tr')
      .first()
      .find('textarea')
      .first()
      .should('have.value', 'recover this draft')
    cy.get('@receipt.all').should('have.length', 0)
    cy.intercept('GET', '**/students/teams/1', ok(roster))
    cy.get('[data-cy=evaluation-reload]').click()
    cy.get('[data-cy=evaluation-submit]').should('be.disabled')
    cy.get('[data-cy=evaluation-recovery]').should('contain', 'outcomes are still unknown')
    cy.get('@unknown.all').should('have.length', 2)
    // Only a positive stored outcome resolves the unknown rows; an empty read did not.
    cy.intercept('GET', '**/evaluations/evaluators/4/week/*', ok([row(14, 4), row(15, 5)]))
    cy.get('[data-cy=evaluation-reload]').click()
    cy.get('[data-cy=evaluation-submit]').should('be.enabled')
    cy.get('.el-table__body tr')
      .first()
      .find('textarea')
      .first()
      .should('have.value', 'recover this draft')
  })

  it('retains an inactive unresolved draft after timeout and empty read, then attaches the late commit ID (review R3)', () => {
    visit(
      [],
      roster.map((member) => ({ ...member, enabled: true }))
    )
    cy.get('.el-table__body tr').eq(2).find('textarea').first().type('keep this unresolved draft')
    let committed = false
    let originalDraft
    cy.intercept('POST', '**/evaluations', (req) => {
      if (req.body.evaluateeId === 6) {
        originalDraft = req.body
        req.reply({ delay: 11000, body: ok({ ...req.body, evaluationId: 16 }) })
      } else req.reply(ok({ ...req.body, evaluationId: req.body.evaluateeId === 4 ? 14 : 15 }))
    }).as('originalCreates')
    cy.intercept('GET', '**/students/teams/1', ok(roster))
    cy.intercept('GET', '**/evaluations/evaluators/4/week/*', (req) => {
      const stored = [row(14, 4), row(15, 5)]
      if (committed) stored.push({ ...originalDraft, evaluationId: 16 })
      req.reply(ok(stored))
    }).as('recoveryRead')
    cy.get('[data-cy=evaluation-submit]').click()
    cy.get('[data-cy=evaluation-recovery]', { timeout: 15000 }).should(
      'contain',
      'outcomes are still unknown'
    )
    cy.get('.el-table__body tr').should('have.length', 3)
    cy.get('.el-table__body tr')
      .eq(2)
      .find('textarea')
      .first()
      .should('have.value', 'keep this unresolved draft')
    cy.get('[data-cy=evaluation-submit]').should('be.disabled').click({ force: true })
    cy.get('@originalCreates.all').should('have.length', 3)
    cy.get('@receipt.all').should('have.length', 0)
    cy.get('[data-cy=evaluation-reload]').click()
    cy.get('[data-cy=evaluation-submit]').should('be.disabled')
    cy.get('.el-table__body tr').should('have.length', 3)
    // Release the simulated original commit after the empty recovery read, as in the MySQL regression.
    cy.then(() => {
      committed = true
    })
    cy.get('[data-cy=evaluation-reload]').click()
    cy.get('[data-cy=evaluation-submit]').should('be.enabled')
    cy.get('.el-table__body tr')
      .eq(2)
      .find('textarea')
      .first()
      .should('have.value', 'keep this unresolved draft')
    cy.intercept('PUT', '**/evaluations/*', (req) => req.reply(ok(req.body))).as('knownEdits')
    cy.get('[data-cy=evaluation-submit]').click()
    cy.get('@knownEdits.all').should('have.length', 3)
    cy.get('@knownEdits.all').then((calls) => {
      const savedInactive = calls.find((call) => call.request.url.endsWith('/16'))
      expect(savedInactive.request.body.publicComment).to.eq('keep this unresolved draft')
    })
    cy.get('@originalCreates.all').should('have.length', 3)
    cy.wait('@receipt')
  })
})
