package com.evenup.sample.rest.client.swing

import groovy.swing.SwingBuilder

import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

import javax.swing.*

import com.evenup.sample.rest.accounts.Account;
import com.evenup.sample.rest.accounts.AccountCollection
import com.evenup.sample.rest.client.AddInvitationAction
import com.evenup.sample.rest.client.GenericGetAction;
import com.evenup.sample.rest.client.JsonWriter
import com.evenup.sample.rest.client.LoginAction
import com.evenup.sample.rest.client.PartnerDetailsAction
import com.evenup.sample.rest.client.RefreshAccountCollectionAction;
import com.evenup.sample.rest.client.Session
import com.evenup.sample.rest.client.SetRESTCallbackAction
import com.evenup.sample.rest.client.TemplateEventAction;
import com.evenup.sample.rest.server.JettyRESTServer

/**
 * This is the main entry point into the client GUI.  The
 * GUI components are all defined in {@link #run()}.
 * 
 * Copyright 2014 EvenUp, Inc.
 *
 * THIS CODE IS INTENDED AS AN EXAMPLE ONLY.  IT HAS NOT BEEN TESTED AND 
 * SHOULD NOT BE USED IN A PRODUCTION ENVIRONMENT.
 * 
 * THE  CODE IS  PROVIDED "AS  IS",  WITHOUT WARRANTY  OF ANY  KIND, EXPRESS  
 * OR IMPLIED,  INCLUDING BUT  NOT LIMITED  TO THE WARRANTIES  OF 
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 *
 * @author Kevin G. McManus
 * 
 */
class MainClientView {
    
    public MainClientView(accountDBPath) {
        accountCollection = new AccountCollection(accountDBPath)
    }
    
    public MainClientView() {
        def homeDir = System.getProperty("user.home")
        def dirName = homeDir + '/.evenup-sample'
        new File(dirName).mkdir()
        accountCollection = new AccountCollection(dirName + '/accountDB')
    }

    Executor executor = Executors.newFixedThreadPool(4);
    
    // TODO we should add a resource for these and make this dynamic
    static String[] BUILT_IN_VARIABLES = ["partner.name", "partner.phoneNumber", "member.firstName",
            "member.lastName", "account.name", "event.creationTime"]

    AccountCollection accountCollection 
    JettyRESTServer restServer
    BlockingQueue<String> messageQ = new LinkedBlockingQueue<String>()
    SwingMessageQueueListener mqListener

    LoginAction loginAction
    SetRESTCallbackAction setRESTCallbackAction
    AddInvitationAction addInvitationAction
    GenericGetAction partnerDetailsAction
    TemplateEventAction templateEventAction
    GenericGetAction partnerTemplatesAction
    RefreshAccountCollectionAction refreshAccountsAction
    
    // used to build all Swing objects.
    def swing = new SwingBuilder()

    def frame
    // The session hold a few bits of data and is a result of logging in
    // see LoginAction.
    Session session

    // holds a copy of the data sent to EvenUp on the last request
    JTextArea toREST

    // holds a copy of the data received from EvenUp on the last request
    JTextArea fromREST

    // holds all callbacks received from EvenUp.  This appends...
    JTextArea callbackREST

    // a goofy interface that is used to write to the toRest and
    // fromRest textAreas
    JsonWriter jWriter

    // an attempt to consolidate how all these dialogs are created.
    private createDialog(title) {
        return swing.dialog(title: title, 
                modal:true, alwaysOnTop: true)
    }
    
    private prepAndShowDialog(dialog, panel) {
        dialog.getContentPane().add(panel)
        dialog.pack()
        dialog.setLocationRelativeTo(frame)
        dialog.show()
    }
    
    def login() {
        def loginDialog = createDialog('Login')
        def panel = swing.panel{
            vbox {
                hbox{
                    label(text: 'Host ')
                    textField(columns: 20, id: 'host')
                }
                hbox{
                    label(text: 'Email ')
                    textField(columns: 20, id: 'name')
                }
                hbox{
                    label(text: 'Password ')
                    passwordField(columns: 20, id: 'password')
                }
                hbox{
                    button(action: action(id: 'okButton', name: 'OK', closure: {
                        session = loginAction.login(host.text, name.text, password.text); 
                        loginDialog.dispose()}, defaultButton: true))
                    button('Cancel', actionPerformed: {loginDialog.dispose()})
                }
            }
        }
        prepAndShowDialog(loginDialog, panel)
    }

    def sessionCheck() {
        if (session == null) {
            def pane = swing.optionPane(message:'You must log in first.')
                    def dialog = pane.createDialog(frame, 'Error')
                    dialog.show()
                    return false
        }
        return true
    }
    
    def showPartnerDetails() {
        if (sessionCheck()) {            
            partnerDetailsAction.get(session, session.getPartnerUri())
        }
    }
    
    def setRESTCallback() {
        if (sessionCheck()) {
            def dialog = createDialog('Set REST Callback Endpoint')
            def panel = swing.panel{
                vbox {
                    hbox{
                        label(text: 'HOST ')
                        textField(columns: 30, id: 'host')
                    }
                    hbox{
                        label(text: 'URI for Callbacks')
                        textField(columns: 35, id: 'uri', 
                            text: bind(
                                source:host, 
                                sourceProperty:'text', 
                                converter: { v ->  v ? "$v/events": ''}))
                    }
                    hbox{
                        button('OK', actionPerformed: {
                            setRESTCallbackAction.setRESTCallback(session, uri.text); 
                            dialog.dispose()})
                        button('Cancel', actionPerformed: {dialog.dispose()})
                    }
                }
            }
            prepAndShowDialog(dialog, panel)
        }
    }

    /**
     * Present a dialog to ask for account number and then call EvenUp to
     * generate an invitation token.
     */
    def addInvitation() {
        if (sessionCheck()) {
            def dialog = createDialog('Add Invitation')
            def panel = swing.panel{
                vbox {
                    hbox{
                        label(text: 'Account Number ')
                        textField(columns: 20, id: 'accountNumber')
                    }
                    hbox{
                        button('OK', actionPerformed: {
                            def result = addInvitationAction.addInvite(session, accountNumber.text);
                            dialog.dispose();
                            swing.optionPane().showMessageDialog(null, new JTextArea(
                                """An invitation was created, with code \"${result[0].code}\" and zip \"${result[0].zip}\".
You can enter this in the UI to create an account."""),
                                "Invitation Code",
                                JOptionPane.INFORMATION_MESSAGE)
                            })
                        button('Cancel', actionPerformed: {dialog.dispose()})
                    }
                }
            }
            prepAndShowDialog(dialog, panel)
        }
    }
    
    /**
     * Present the user with all the fields in the template and choice of reply
     * templates.  On OK, send the REST server a TEMPLATE event.
     * 
     * @param accountNum used to get the Account our of the AccountCollection.
     * @param template the template to create an event for
     * @param templates the templates to present for the reply template
     */
    def sendTemplateEvent(accountNum, template, templates) {
        if (sessionCheck()) {
            
            Account account = accountCollection.getForNumber(accountNum)
            // find all the variables in the template and present them in a form:
            def matcher = template.en.templateText =~ /\$\{(.*?)\}/
            def fieldTypes = template.fieldTypes
            def dialog = createDialog('Template Event')
            def templateIds = ['None'] + templates.collect({it.id})
            def panel = swing.panel {
                JComboBox<String> templateChosen
                def templateFields = [:]
                vbox {
                    // this will go through all substrings in the template text
                    // that match the regex above.  "it" is groovy's magical variable
                    // used in a closure when iterating over collections to reference 
                    // the item in the collection.
                    matcher.each {
                        def fieldName = it[1]
                        // treat field names as unique
                        if (!(fieldName in templateFields) && !(fieldName in BUILT_IN_VARIABLES)) {
                            hbox {
                                label(text: "${fieldName} [${fieldTypes[fieldName]}]")
                                def fieldId = fieldName + '-id'
                                templateFields[fieldName] = textField(columns: 30, id: fieldId)
                            }
                        }
                    }
                    hbox{
                        label(text: 'Reply Template')
                        templateChosen = comboBox(items: templateIds)
                    }
                    hbox{
                        button('Send', actionPerformed: {templateEventAction.sendTemplateEvent(session, 
                            account.getAccountResourceURI(), 
                            template.id, 
                            templateChosen.selectedItemReminder.equals('None') ? null : templateChosen.selectedItemReminder, 
                            templateFields.collectEntries([:]) {k,v -> [k, v.text]}); 
                            dialog.dispose()})
                        button('Cancel', actionPerformed: {dialog.dispose()})
                    }
                }
            }
            prepAndShowDialog(dialog, panel)
        }
    }
    
    /**
     * Present dialog that asks for account and template in dropdowns.  This then
     * calls sendTemplateEvent to finish the job.
     */
    def prepareTemplateEvent() {
        if (sessionCheck()) {
            def templates = partnerTemplatesAction.get(session, "${session.getPartnerUri()}/templates")
            
            // build a map of templates by their ids
            def templateMap = [:]
            templates.each {
                templateMap[it.id] = it
            }
            
            def dialog = swing.dialog(title: 'Accounts', modal:true, 
                                            alwaysOnTop: true, locationRelativeTo: null, 
                                            resizable: true)
            def panel = swing.panel {
                JComboBox<String> account
                JComboBox<String> template
                vbox {
                    hbox{
                        label(text: 'Choose Account')
                        account = comboBox(items:accountCollection.getAccountNumbers())
                    }
                    hbox{
                        label(text: 'Choose Template')
                        template = comboBox(items:templates.collect({it.id}))
                    }
                    hbox{
                        button('OK', actionPerformed: {
                            sendTemplateEvent(account.selectedItem, templateMap[template.selectedItem], templates); 
                            dialog.dispose()})
                        button('Cancel', actionPerformed: {dialog.dispose()})
                    }
                }
            }
            prepAndShowDialog(dialog, panel)
        }
    }

    /**
     * Starts the REST server if it is not already running.  Presents a dialog to ask for port.
     */
    def startServer() {
        if (restServer.isRunning()) {
            def pane = swing.optionPane(message:'REST server is already running.')
            def dialog = pane.createDialog(frame, 'Error')
            dialog.show()
            return
        }

        def serverDialog = createDialog('Start REST Server')
        def panel = swing.panel (preferredSize: [350, 150]){
            vbox {
                hbox{
                    label(text: 'Port ')
                    textField(columns: 20, id: 'port')
                }
                hbox{
                    textArea(text: 'This will start an HTTP server to handle REST calls from EvenUp.  Note that your firewall will need to allow traffic to this port to be successful.',
                    lineWrap: true, wrapStyleWord: true)
                }
                hbox{
                    button('OK', actionPerformed: {
                        restServer.setPort(Integer.valueOf(port.text));
                        executor.execute(restServer); 
                        serverDialog.dispose()})
                    button('Cancel', actionPerformed: {serverDialog.dispose()})
                }
            }
        }
        prepAndShowDialog(serverDialog, panel)
    }

    /**
     * Stops the REST server.
     */
    def stopServer() {
        restServer.stop();
    }

    def close() {
        stopServer();
        mqListener.stop = true
    }

    def createActions(jWriter) {
        loginAction = new LoginAction(jsonWriter: jWriter)
        setRESTCallbackAction = new SetRESTCallbackAction(jsonWriter: jWriter)
        addInvitationAction = new AddInvitationAction(jsonWriter: jWriter)
        partnerDetailsAction = new GenericGetAction(jsonWriter: jWriter)
        partnerTemplatesAction = new GenericGetAction(jsonWriter: jWriter)
        templateEventAction = new TemplateEventAction(jsonWriter: jWriter)
        refreshAccountsAction = new RefreshAccountCollectionAction(jsonWriter: jWriter, 
            accountCollection: accountCollection)
    }
    

    /**
     * This my first attempt at using SwingBuilder.  It is meant to 
     * set up a simple frame that has a bunch of menu items to perform 
     * REST calls to EvenUp's Servers.
     * 
     */
    void run() {
        swing.edt {
            frame = frame(title: 'Partner REST Test', defaultCloseOperation: JFrame.EXIT_ON_CLOSE,
            size: [800, 600], show: true, locationRelativeTo: null, windowClosed: {close()}) {
                lookAndFeel("system")
                menuBar() {
                    menu(text: "File", mnemonic: 'F') {
                        menuItem(text: "Exit", mnemonic: 'X', actionPerformed: {dispose() })
                    }
                    menu(text: "Partner", mnemonic: 'P') {
                        menuItem(text: 'Login', mnemonic: 'L', actionPerformed: {login() })
                        menuItem(text: 'View Details', mnemonic: 'D', actionPerformed: {showPartnerDetails()})
                        menuItem(text: 'Set REST Callback', mnemonic: 'N', actionPerformed: {setRESTCallback()})
                    }
                    menu(text: "Accounts", mnemonic: 'A') {
                        menuItem(text: 'Add Invitation', mnemonic: 'I', actionPerformed: {addInvitation()})
                        menuItem(text: 'Send Template Event', mnemonic: 'T', actionPerformed: {prepareTemplateEvent()})
                        menuItem(text: 'Refresh Account Collection', actionPerformed: {
                            if (sessionCheck()) refreshAccountsAction.refreshAccounts(session)})
                    }
                    menu(text: "Callback Server") {
                        menuItem(text: 'Start', mnemonic: 'S', actionPerformed: {startServer()})
                        menuItem(text: 'Stop', mnemonic: 'T', actionPerformed: {stopServer()})
                    }
                }

                splitPane (orientation:JSplitPane.VERTICAL_SPLIT, dividerLocation:400){

                    splitPane (dividerLocation:400) {
                        scrollPane(constraints: "left", border: titledBorder(title:  'TO SERVER')) {
                            toREST = textArea(editable: false, lineWrap: true)
                        }
                        scrollPane(constraints: "right", border: titledBorder(title: 'FROM SERVER')) {
                            fromREST = textArea(editable: false, lineWrap: true)
                        }
                    }
                    scrollPane(constraints: "bottom", border: titledBorder(title:  'REST CALLBACKS')) {
                        callbackREST = textArea(editable: false, lineWrap: true)
                    }
                }
            }
        }

        jWriter = new SwingJsonWriter(from: fromREST, to: toREST)
        mqListener = new SwingMessageQueueListener(messageQ, callbackREST)
        executor.execute(mqListener)
        createActions(jWriter)
        // i made 9000 the default, but it should get set by the gui
        restServer = new JettyRESTServer(9000, messageQ, accountCollection)
    }

    static void main(args) {
        def mv = new MainClientView()
        mv.run()
    }
}