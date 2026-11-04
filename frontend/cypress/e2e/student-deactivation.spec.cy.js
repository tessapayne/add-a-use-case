// UC-STU-deactivate-student: T-main, T-cancel, T-failure, T-refresh, T-restore, T-jwt.
const student = {
  id: 4,
  username: 'j.smith@abc.edu',
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  enabled: true,
  sectionId: 2,
  sectionName: '2023-2024',
  teamId: 1,
  teamName: 'Team1'
}
const result = (data) => ({ flag: true, code: 200, message: 'Success', data })
const page = (data) => result({ content: data, number: 0, size: 50, totalElements: data.length })
function visitAs(username, path) {
  cy.request({
    method: 'POST',
    url: 'http://localhost/api/v1/users/login',
    auth: { username, password: '123456' }
  }).then(({ body }) => {
    cy.visit(path, {
      onBeforeLoad(win) {
        win.localStorage.setItem('token', JSON.stringify({ token: body.data.token }))
        win.localStorage.setItem('userInfo', JSON.stringify({ userInfo: body.data.userInfo }))
        win.localStorage.setItem(
          'settings',
          JSON.stringify({ defaultSectionId: 2, defaultCourseId: 1 })
        )
      }
    })
  })
}
function open(active = true) {
  cy.intercept('POST', '**/students/search*', page([{ ...student, enabled: active }])).as('roster')
  cy.intercept('GET', '**/students/4', result({ ...student, enabled: active })).as('details')
  visitAs('b.wei@abc.edu', '/students')
  cy.wait('@roster')
}
function confirmDialog() {
  cy.get('[data-cy=deactivate-open]').click()
  cy.wait('@details')
}

describe('Student deactivation confirmation and recovery', () => {
  it('shows identity, scope and consequences before one confirmed request; keyboard cancel sends nothing (T-main/T-cancel)', () => {
    cy.intercept('POST', '**/students/4/deactivate', result({ ...student, enabled: false })).as(
      'deactivate'
    )
    open()
    confirmDialog()
    cy.get('[data-cy=deactivate-dialog]')
      .should('contain', 'John Smith')
      .and('contain', 'Team1')
      .and('contain', 'Course enrollment')
      .and('contain', 'historical scores')
    cy.screenshot('deactivation-confirmation', { capture: 'viewport' })
    cy.get('[data-cy=deactivate-cancel]').focus().should('have.focus').type('{enter}')
    cy.get('@deactivate.all').should('have.length', 0)
    confirmDialog()
    cy.get('[data-cy=deactivate-confirm]').focus().should('have.focus').type('{enter}')
    cy.wait('@deactivate').its('request.body').should('be.empty')
    cy.contains('Student deactivated successfully').should('be.visible')
    cy.get('@deactivate.all').should('have.length', 1)
  })

  it('disables duplicate confirmation and retains acknowledged inactive status when refresh fails (6d)', () => {
    open()
    confirmDialog()
    cy.intercept('POST', '**/students/4/deactivate', {
      delay: 600,
      body: result({ ...student, enabled: false })
    }).as('deactivate')
    cy.intercept('POST', '**/students/search*', {
      statusCode: 500,
      body: { flag: false, code: 500, message: 'Refresh failed' }
    }).as('refresh')
    cy.get('[data-cy=deactivate-confirm]').click().should('be.disabled').click({ force: true })
    cy.wait('@deactivate')
    cy.wait('@refresh')
    cy.get('[data-cy=student-refresh-warning]').should(
      'contain',
      'Student deactivated. The list could not be refreshed'
    )
    cy.get('.el-table__body').should('contain', 'Inactive')
    cy.get('@deactivate.all').should('have.length', 1)
  })

  it('does not claim success for a server failure and permits a safe retry (6c/6a)', () => {
    open()
    confirmDialog()
    let calls = 0
    cy.intercept('POST', '**/students/4/deactivate', (req) => {
      if (++calls === 1)
        req.reply({ statusCode: 500, body: { flag: false, code: 500, message: 'Write failed' } })
      else req.reply(result({ ...student, enabled: false }))
    }).as('mutation')
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@mutation')
    cy.get('[data-cy=deactivate-error]').should('contain', 'not acknowledged')
    cy.contains('Student deactivated successfully').should('not.exist')
    cy.get('.el-table__body').should('contain', 'Active')
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@mutation')
    cy.contains('Student deactivated successfully').should('be.visible')
  })

  it('offers reload after a lost response without claiming rollback or success (6c)', () => {
    cy.intercept('POST', '**/students/4/deactivate', {
      delay: 11000,
      body: result({ ...student, enabled: false })
    }).as('lost')
    open()
    confirmDialog()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.get('[data-cy=deactivate-error]', { timeout: 15000 }).should(
      'contain',
      'may already have succeeded'
    )
    cy.intercept('GET', '**/students/4', result({ ...student, enabled: false }))
    cy.contains('button', 'Reload current state').click()
    cy.get('[data-cy=deactivate-dialog]').should('contain', 'Inactive')
    cy.contains('Student deactivated successfully').should('not.exist')
    cy.get('@lost.all').should('have.length', 1)
  })

  it('keeps status read-only, preserves draft on cancelled restoration, and sends explicit confirmed intent (T-restore)', () => {
    open(false)
    cy.get('button[aria-label="Edit student"]').click()
    cy.wait('@details')
    cy.get('[data-cy=student-profile-status]').should('contain', 'Inactive')
    cy.get('.el-dialog .el-switch').should('not.exist')
    cy.get('.el-dialog input').eq(2).clear().type('Draft John')
    cy.intercept('PUT', '**/students/4*', result({ ...student, firstName: 'Draft John' })).as(
      'save'
    )
    cy.get('[data-cy=student-restore]').click()
    cy.get('.el-message-box').contains('button', 'Cancel').click()
    cy.get('.el-dialog input').eq(2).should('have.value', 'Draft John')
    cy.get('@save.all').should('have.length', 0)
    cy.get('[data-cy=student-restore]').click()
    cy.get('.el-message-box').contains('button', 'Save changes and reactivate').click()
    cy.wait('@save').then(({ request }) => {
      expect(request.query.reactivate).to.eq('true')
      expect(request.body.firstName).to.eq('Draft John')
    })
    cy.contains('Student profile saved and access restored').should('be.visible')
  })

  it('ordinary saving omits restoration intent (T-profile)', () => {
    open(false)
    cy.get('button[aria-label="Edit student"]').click()
    cy.wait('@details')
    cy.intercept('PUT', '**/students/4*', result({ ...student, enabled: false })).as('save')
    cy.get('[data-cy=student-profile-save]').click()
    cy.wait('@save').its('request.query').should('not.have.property', 'reactivate')
  })

  it('503 preserves login state and allows retry, while 401 clears login (T-jwt)', () => {
    open()
    cy.intercept('GET', '**/students/4', {
      statusCode: 503,
      body: {
        flag: false,
        code: 503,
        message: 'Account verification is temporarily unavailable. Please try again.'
      }
    }).as('unavailable')
    cy.get('[data-cy=deactivate-open]').click()
    cy.wait('@unavailable')
    cy.location('pathname').should('eq', '/students')
    cy.window().then((win) =>
      expect(JSON.parse(win.localStorage.getItem('token')).token).not.to.eq('')
    )
    cy.intercept('GET', '**/students/4', {
      statusCode: 401,
      body: { flag: false, code: 401, message: 'Invalid account' }
    }).as('disabled')
    cy.get('[data-cy=deactivate-open]').click()
    cy.wait('@disabled')
    cy.location('pathname').should('eq', '/login')
    cy.window().then((win) => expect(JSON.parse(win.localStorage.getItem('token')).token).to.eq(''))
  })
})

describe('Live app lifecycle verification', () => {
  let adminToken
  let original
  afterEach(() => {
    if (adminToken && original)
      cy.request({
        method: 'PUT',
        url: 'http://localhost/api/v1/students/4?reactivate=true',
        headers: { Authorization: adminToken },
        body: original
      })
        .its('body.flag')
        .should('eq', true)
  })
  it('deactivates through the real dialog, rejects an issued token after commit, and restores the fixture', () => {
    cy.request({
      method: 'POST',
      url: 'http://localhost/api/v1/users/login',
      auth: { username: 'b.wei@abc.edu', password: '123456' }
    }).then(({ body }) => {
      adminToken = 'Bearer ' + body.data.token
      cy.request({
        url: 'http://localhost/api/v1/students/4',
        headers: { Authorization: adminToken }
      }).then(({ body: details }) => {
        original = details.data
        cy.request({
          method: 'PUT',
          url: 'http://localhost/api/v1/students/4?reactivate=true',
          headers: { Authorization: adminToken },
          body: original
        })
      })
    })
    let studentToken
    cy.request({
      method: 'POST',
      url: 'http://localhost/api/v1/users/login',
      auth: { username: 'j.smith@abc.edu', password: '123456' }
    }).then(({ body }) => {
      studentToken = 'Bearer ' + body.data.token
    })
    cy.intercept('POST', '**/students/4/deactivate').as('liveDeactivate')
    visitAs('b.wei@abc.edu', '/students')
    cy.contains('.el-table__body tr', 'j.smith@abc.edu').find('[data-cy=deactivate-open]').click()
    cy.get('[data-cy=deactivate-dialog]').should('contain', 'John Smith')
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@liveDeactivate').its('response.body.data.enabled').should('eq', false)
    cy.contains('.el-table__body tr', 'j.smith@abc.edu').should('contain', 'Inactive')
    cy.then(() =>
      cy.request({
        url: 'http://localhost/api/v1/students/4',
        headers: { Authorization: studentToken },
        failOnStatusCode: false
      })
    ).then(({ status, body }) => {
      expect(status).to.eq(401)
      expect(body.flag).to.eq(false)
      expect(body.code).to.eq(401)
    })
  })
})
