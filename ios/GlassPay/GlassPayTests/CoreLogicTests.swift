import Foundation
import Testing
@testable import GlassPay

// Casos traducidos de ChargeStateTest, ChargeNavigationTest, ResolveStateTest, QrTokenParserTest,
// AppStateTest y StatsTest de Android: la lógica pura debe comportarse igual en ambas apps.

private let proposal = ChargeProposal(proposalId: "p1", amount: "350.00", currency: "MXN", concept: "cena", expiresAt: "2099-01-01T00:00:00Z")

private func machineInPreview(remaining: Int = 60) -> ChargeStateMachine {
    var machine = ChargeStateMachine()
    machine.beginListening()
    machine.beginInterpretation()
    machine.preview(proposal, remainingSeconds: remaining)
    return machine
}

@Suite struct CommandNormalizationTests {
    @Test func stripsAccentsPunctuationAndCase() {
        #expect(normalizeCommandText("  ¡Sí!  ") == "si")
        #expect(normalizeCommandText("«Confirmar».") == "confirmar")
        #expect(normalizeCommandText("Empezar   de  nuevo…") == "empezar de nuevo")
    }

    @Test func confirmationRequiresExactPhrase() {
        #expect(isConfirmation("Sí"))
        #expect(isConfirmation("confirmar."))
        #expect(!isConfirmation("sí, confirmar"))
        #expect(!isConfirmation("confirmar por favor"))
        #expect(!isConfirmation(""))
    }

    @Test func cancellationWinsOverConfirmation() {
        let state = machineInPreview().state
        #expect(voiceCommand("no", state: state) == .cancel)
        #expect(voiceCommand("cancelar", state: state) == .cancel)
        #expect(voiceCommand("empezar de nuevo", state: state) == .startOver)
        #expect(voiceCommand("sí", state: state) == .confirm)
    }

    @Test func confirmOnlyCountsInPreview() {
        #expect(voiceCommand("sí", state: .listening) == .none)
        #expect(voiceCommand("confirmar", state: .ready()) == .none)
    }

    @Test func chargeVerbStartsNewSession() {
        #expect(startsWithChargeVerb("Cóbrale 350 por la cena"))
        #expect(startsWithChargeVerb("cobrar 100"))
        #expect(!startsWithChargeVerb("no, mejor 400"))
    }
}

@Suite struct ChargeInputSessionTests {
    @Test func clarificationConcatenates() throws {
        let session = try #require(ChargeInputSession.start(resolutionId: "r", text: "cóbrale 350 por la cena"))
        guard case let .accepted(next) = session.appendClarification("no, mejor 400") else { Issue.record(); return }
        #expect(next.text == "cóbrale 350 por la cena no, mejor 400")
        #expect(next.clarificationRounds == 1)
    }

    @Test func chargeVerbReplacesSession() throws {
        let session = try #require(ChargeInputSession.start(resolutionId: "r", text: "cóbrale 350"))
        guard case let .accepted(next) = session.appendClarification("Cobrar 200 por tacos") else { Issue.record(); return }
        #expect(next.text == "Cobrar 200 por tacos")
        #expect(next.clarificationRounds == 0)
    }

    @Test func limitsRoundsAndLength() throws {
        var session = try #require(ChargeInputSession.start(resolutionId: "r", text: "cóbrale"))
        for _ in 0..<maxClarificationRounds {
            guard case let .accepted(next) = session.appendClarification("algo") else { Issue.record(); return }
            session = next
        }
        #expect(session.appendClarification("otra") == .tooManyRounds)
        #expect(ChargeInputSession.start(resolutionId: "r", text: String(repeating: "a", count: 481)) == nil)
        #expect(session.appendClarification("   ") == .invalid)
    }

    @Test func definitiveErrorClearsSessionOthersKeepIt() {
        let session = ChargeInputSession.start(resolutionId: "r", text: "cóbrale 100")
        #expect(inputSessionAfterDecision(session, reason: .interpretDefinitiveError) == nil)
        #expect(inputSessionAfterDecision(session, reason: .interpretNetwork) == session)
        #expect(inputSessionAfterDecision(session, reason: .voiceError) == session)
    }
}

@Suite struct ChargeStateMachineTests {
    @Test func happyPath() {
        var machine = machineInPreview()
        machine.beginConfirmation()
        #expect(machine.state == .confirming)
        machine.created(requestId: "req", statusToken: "tok")
        machine.paymentUpdated(.paid)
        #expect(machine.state == .created(requestId: "req", statusToken: "tok", payment: .paid))
    }

    @Test func expiredProposalCannotBeConfirmed() {
        var machine = machineInPreview(remaining: 0)
        machine.beginConfirmation()
        #expect(machine.state.isPreview)
        #expect(availableActions(machine.state) == [.back])
    }

    @Test func tickToZeroReturnsToReady() {
        var machine = machineInPreview()
        machine.tick(remainingSeconds: 0)
        #expect(machine.state == .ready(message: "La propuesta venció. Dicta el cobro otra vez."))
    }

    @Test func failureOnlyFromInterpretingOrInvalidConfirmation() {
        var ready = ChargeStateMachine()
        ready.failure(.network)
        #expect(ready.state == .ready())

        var confirming = machineInPreview()
        confirming.beginConfirmation()
        confirming.failure(.network)
        #expect(confirming.state == .confirming)
        confirming.failure(.proposalInvalid)
        #expect(confirming.state.isReady)
    }

    @Test func confirmationFailureIsNotRecoverable() {
        var machine = machineInPreview()
        machine.beginConfirmation()
        machine.confirmationFailure()
        #expect(availableActions(machine.state) == [.scanAnother])
    }

    @Test func stopWaitingKeepsHonestMessage() {
        var machine = machineInPreview()
        machine.beginConfirmation()
        machine.created(requestId: "req", statusToken: "tok")
        machine.paymentUpdated(.approved)
        machine.stopWaiting()
        #expect(machine.state == .ready(message: stopWaitingApprovedMessage))
    }

    @Test func actionsPerState() {
        #expect(availableActions(.ready()) == [.dictate, .scanAnother])
        #expect(availableActions(.listening).isEmpty)
        #expect(availableActions(.clarification(question: "?")) == [.answer, .startOver, .scanAnother])
        #expect(availableActions(machineInPreview().state) == [.confirm, .cancel, .startOver, .back])
        #expect(availableActions(.created(requestId: "r", statusToken: "t")) == [.stopWaiting])
        #expect(availableActions(.error(message: "x", recoverable: true)) == [.retry, .scanAnother])
    }

    @Test func onlyPagadaIsPaid() {
        #expect(paymentTrackingFor(serverState: "pagada") == .paid)
        #expect(paymentTrackingFor(serverState: "aprobada") == .approved)
        #expect(paymentStep(.approved) == .confirming)
        if case .error = paymentTrackingFor(serverState: "creada") {} else { Issue.record("creada no debe ser un estado válido") }
    }
}

@Suite struct ResolveAndQrTests {
    private let resolution = PayerResolution(displayName: "Ana", resolutionId: "res", expiresAt: "2099-01-01T00:00:00Z", contactId: nil)

    @Test func lateResultIsIgnored() {
        var machine = ResolveStateMachine()
        machine.dispatch(.begin(attemptId: 1, token: "aaa", waking: false))
        machine.dispatch(.begin(attemptId: 2, token: "bbb", waking: false))
        machine.dispatch(.success(attemptId: 1, resolution: resolution))
        #expect(machine.state.isResolving)
        machine.dispatch(.success(attemptId: 2, resolution: resolution))
        #expect(machine.state.isIdentified)
    }

    @Test func networkFailureKeepsTokenForRetry() {
        var machine = ResolveStateMachine()
        machine.dispatch(.begin(attemptId: 1, token: "tok", waking: false))
        machine.dispatch(.failure(attemptId: 1, type: .network))
        guard case let .retryableError(_, token, _) = machine.state else { Issue.record(); return }
        #expect(token == "tok")
    }

    @Test func notFoundReturnsToScanning() {
        var machine = ResolveStateMachine()
        machine.dispatch(.begin(attemptId: 1, token: "tok", waking: false))
        machine.dispatch(.failure(attemptId: 1, type: .notFound))
        #expect(machine.state == .scanning)
    }

    @Test func qrPrefixIsRequired() {
        let token = String(repeating: "a", count: 43)
        #expect(QrTokenParser.extractToken("glasspay:\(token)") == token)
        #expect(QrTokenParser.extractToken(token) == nil)
        #expect(QrTokenParser.extractToken("glasspay:short") == nil)
        #expect(QrTokenParser.extractToken("glasspay:\(token)/../x") == nil)
        #expect(QrTokenParser.extractToken(nil) == nil)
    }
}

@Suite struct FormattingTests {
    @Test func money() {
        #expect(formatMoney("350", "MXN") == "$350.00 MXN")
        #expect(formatMoney("1200.5", "MXN") == "$1200.50 MXN")
        #expect(formatMoney("abc", "MXN") == "abc MXN")
        #expect(maskAmount("$350.00 MXN", visible: false) == "$ •••• MXN")
        #expect(formatGroupedMoney(3450) == "$3,450.00")
        #expect(formatGroupedMoney(-20, signed: true) == "−$20.00")
        #expect(formatGroupedMoney(20, signed: true) == "+$20.00")
    }

    @Test func approveUrlAllowList() {
        #expect(isAllowedApproveUrl("https://www.sandbox.paypal.com/checkoutnow?token=1"))
        #expect(!isAllowedApproveUrl("http://www.sandbox.paypal.com/x"))
        #expect(!isAllowedApproveUrl("https://www.paypal.com/x"))
        #expect(!isAllowedApproveUrl("https://user@www.sandbox.paypal.com/x"))
        #expect(!isAllowedApproveUrl("https://www.sandbox.paypal.com:8443/x"))
        #expect(!isAllowedApproveUrl(nil))
    }

    @Test func amountInputNeverExceedsWhatWasTyped() {
        #expect(parseAmountInput("100") == "100.00")
        #expect(parseAmountInput("99,5") == "99.50")
        #expect(parseAmountInput("1000.01") == nil)
        #expect(parseAmountInput("0") == nil)
        #expect(parseAmountInput("1.234") == nil)
        #expect(parseAmountInput("-5") == nil)
    }

    @Test func tabSwitchBlockedDuringCharge() {
        let identified = ResolveState.identified(displayName: "Ana", resolutionId: "r", expiresAt: "", contactId: nil)
        #expect(allowsTabSwitch(resolve: .scanning, charge: .ready()))
        #expect(!allowsTabSwitch(resolve: .resolving(attemptId: 1, token: "t", waking: false), charge: .ready()))
        #expect(!allowsTabSwitch(resolve: identified, charge: .listening))
        #expect(allowsTabSwitch(resolve: identified, charge: .created(requestId: "r", statusToken: "t", payment: .paid)))
    }

    @Test func statsHelpers() {
        #expect(niceAxisMax(0) == 100)
        #expect(niceAxisMax(730) == 1000)
        #expect(niceAxisMax(2100) == 2500)
        #expect(axisLabel(500) == "500")
        #expect(axisLabel(2500) == "2.5k")
        #expect(axisLabel(1000) == "1k")
        #expect(netChangePercent(current: 150, previous: 100) == 50)
        #expect(netChangePercent(current: -10, previous: 100) == nil)
        #expect(netChangePercent(current: 10, previous: 0) == nil)
    }

    @Test func outcomeNeverSaysPaid() {
        let message = proposalOutcomeMessage(.split([
            SplitResult(contactName: "Ana", amount: "150", ok: true, failure: nil),
            SplitResult(contactName: "Luis", amount: "150", ok: false, failure: "x"),
        ]), autoExecute: true)
        #expect(message == "Creé 1 cobro: Ana $150.00. Cada quien lo aprueba en PayPal. No se creó para: Luis.")
        #expect(!message.lowercased().contains("pagad"))
    }
}

@Suite struct DecodingTests {
    @Test func amountsAcceptNumbersOrText() throws {
        let json = #"{"movements":[{"movementId":"m","state":"pagada","amount":350,"currency":"MXN","concept":"c","payerName":"Ana","createdAt":"x","updatedAt":"y"}]}"#
        struct Body: Decodable { let movements: [Movement] }
        let body = try JSONDecoder().decode(Body.self, from: Data(json.utf8))
        #expect(body.movements[0].amount == "350")
        #expect(body.movements[0].kind == "cobro")
    }

    @Test func proposalOutcomeVariants() throws {
        let cancelled = try JSONDecoder().decode(ProposalOutcome.self, from: Data(#"{"cancelled":true}"#.utf8))
        #expect(cancelled == .cancelled)
        #expect(throws: (any Error).self) { try JSONDecoder().decode(ProposalOutcome.self, from: Data("{}".utf8)) }
    }
}
