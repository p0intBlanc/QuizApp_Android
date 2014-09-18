

package com.amcolabs.quizapp.appcontrollers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import android.os.Handler;

import com.amcolabs.quizapp.AppController;
import com.amcolabs.quizapp.QuizApp;
import com.amcolabs.quizapp.User;
import com.amcolabs.quizapp.configuration.Config;
import com.amcolabs.quizapp.databaseutils.Question;
import com.amcolabs.quizapp.databaseutils.Quiz;
import com.amcolabs.quizapp.datalisteners.DataInputListener;
import com.amcolabs.quizapp.screens.ClashScreen;
import com.amcolabs.quizapp.screens.QuestionScreen;
import com.amcolabs.quizapp.screens.WinOrLoseScreen;
import com.amcolabs.quizapp.serverutils.ServerResponse;
import com.amcolabs.quizapp.serverutils.ServerResponse.MessageType;
import com.amcolabs.quizapp.serverutils.ServerWebSocketConnection;
import com.amcolabs.quizapp.uiutils.UiUtils.UiText;
import com.amcolabs.quizapp.widgets.ChallengeView;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

public class ProgressiveQuizController extends AppController{
	User user;
	User user2;
	
	private ServerWebSocketConnection serverSocket;
	
	private Quiz quiz;
		

	
	public ProgressiveQuizController(QuizApp quizApp) {
		super(quizApp);
	}

	
	public void initlializeQuiz(Quiz quiz) {
		this.quiz = quiz;
//		QuestionScreen questionScreen = new QuestionScreen(this);
//		insertScreen(questionScreen);
		showWaitingScreen(quiz);
	}

	ClashScreen clashingScreen = null;
	QuestionScreen questionScreen = null;
	public void showWaitingScreen(Quiz quiz){
		clearScreen();
		clashingScreen = new ClashScreen(this);
		clashingScreen.setClashCount(2); 
		clashingScreen.updateClashScreen(quizApp.getUser()/*quizApp.getUser()*/, 0);//TODO: change to quizApp.getUser()
		insertScreen(clashingScreen);
		quizApp.getServerCalls().startProgressiveQuiz(this, quiz, null);
	}	
	
	public int getMaxScore(){
		if(currentQuestions==null || currentQuestions.size()==0)
			return 0;
		int mscore = 0;
		for(int i=0;i<currentQuestions.size();i++){
			mscore += currentQuestions.get(i).xp*quizApp.getConfig().multiplyFactor(i+1);
		}
		return mscore;
	}
	
	public void showQuestionScreen(ArrayList<User> users){
		for(User user: currentUsers){
			int index = 0;
			if(user.uid!=quizApp.getUser().uid){
				try{
					clashingScreen.updateClashScreen(user, ++index);
				}
				catch(NullPointerException e){
					e.printStackTrace();
				}
			}
		}

		//pre download assets if ever its possible
		questionScreen = new QuestionScreen(this);
		questionScreen.showUserInfo(users,getMaxScore());
		//animate TODO:
		new Handler().postDelayed(new Runnable() {
			
			@Override
			public void run() {
				clearScreen();
				clashingScreen = null; // dispose of it 
				insertScreen(questionScreen);
			}
		}, Config.CLASH_SCREEN_DELAY);
		
		new Handler().postDelayed(new Runnable() {
			
			@Override
			public void run() {
		        userAnswers = new HashMap<String , UserAnswer>();
		        userAnswersStack.clear();
		        currentScore = 0;
				Question currentQuestion = currentQuestions.remove(0);
				questionScreen.animateQuestionChange( UiText.GET_READY.getValue(), UiText.FOR_YOUR_FIRST_QUESTION.getValue() ,currentQuestion);
				if(isBotMode())
					scheduleBotAnswer(currentQuestion);

			}
		}, Config.CLASH_SCREEN_DELAY+Config.PREQUESTION_FADE_OUT_ANIMATION_TIME);
	}
	
	@Override
	public void onDestroy() {
		gracefullyCloseSocket();
	}
	
	int backPressedCount = 0;
	
	@Override
	public boolean onBackPressed() {
		if(quizApp.peekCurrentScreen() instanceof QuestionScreen){
			backPressedCount++;
			if(backPressedCount>1){
				backPressedCount = 0;
				gracefullyCloseSocket();
				return false;
			}
			return true;
		}
		
		else{
			gracefullyCloseSocket();
			return false;
		}
	}
/*
 * Current running quiz
 */
	
	public final  static String QUESTIONS = "1";
	public final  static String CURRENT_QUESTION = "2";
	public final  static String MESSAGE_TYPE = "3";
	public final static String QUESTION_ID = "4";
	public final  static String WHAT_USER_HAS_GOT = "5";
	public final  static String N_CURRENT_QUESTION_ANSWERED = "6";
	public final  static String USER_ANSWER = "7";
	public final static  String USERS="8";
	public final  static String CREATED_AT="9";
	public final  static String ELAPSED_TIME="10";
	
	
	protected static final int CHALLENGE_MODE = 2;
	private static final int BOT_MODE = 3; 
	
	double waitinStartTime = 0;
	boolean noResponseFromServer = true;
	String serverId = null;
	private boolean botMode = false;
	
	private ArrayList<Question> currentQuestions = new ArrayList<Question>();
	private ArrayList<User> currentUsers = new ArrayList<User>();
	private HashMap<String ,UserAnswer> userAnswers;
	private HashMap<String ,List<UserAnswer>> userAnswersStack = new HashMap<String, List<UserAnswer>>();
	
	private int currentScore = 0;
	private int botScore =0;
	protected Random rand = new Random();
	private WinOrLoseScreen quizResultScreen;
	private int quizMode = -1;
	private boolean waitingForRematch;
	
	private void setQuizMode(int mode){
		quizMode = mode;
	}
	private boolean isBotMode(){
		return quizMode == BOT_MODE;
	}
	public boolean isChallengeMode(){
		return quizMode == CHALLENGE_MODE;
	}
	
	private String constructSocketMessage(MessageType messageType , HashMap<String, String> data , HashMap<Integer, String> data1){
		String jsonStr = "{\""+MESSAGE_TYPE+"\":"+Integer.toString(messageType.getValue())+",";
		if(data!=null){
			for(String key:data.keySet()){
				jsonStr+="\""+key+"\":\""+data.get(key)+"\",";
			}
		}
		if(data1!=null){
			for(int key:data1.keySet()){
				jsonStr+="\""+Integer.toString(key)+"\":\""+data.get(key)+"\",";
			}
		}
		jsonStr = jsonStr.substring(0, jsonStr.length()-1); //remove a ,
		return jsonStr+"}";
	}


	public void startSocketConnection(ServerWebSocketConnection mConnection, final Quiz quiz) {
		serverSocket = mConnection; 
		waitinStartTime = Config.getCurrentTimeStamp();

		if(!isChallengeMode()){
			new Handler().postDelayed(new Runnable() {
				@Override
				public void run() {
					if(noResponseFromServer){
						serverSocket.sendTextMessage(constructSocketMessage(MessageType.ACTIVATE_BOT, null, null));
					}
				}
			}, Config.BOT_INTIALIZE_AFTER_NO_USER_TIME);
		}
	}
	
	public static class UserAnswer{
		@SerializedName(MESSAGE_TYPE)
		public int messageType = MessageType.USER_ANSWERED_QUESTION.getValue();
	    @SerializedName(QUESTION_ID)
	    public String questionId;
	    @SerializedName("uid")
	    public String uid;
	    @SerializedName(USER_ANSWER)
	    public String userAnswer;
	    @SerializedName(ELAPSED_TIME)
	    public int elapsedTime;
	    @SerializedName(WHAT_USER_HAS_GOT)
	    public int whatUserGot;
		
		public UserAnswer(String questionId, String uid, String userAnswer, int elapsedTime, int whatUserGot) {
			this.questionId = questionId;
			this.uid = uid;
			this.userAnswer = userAnswer;
			this.elapsedTime = elapsedTime;
			this.whatUserGot = whatUserGot;
		}
	}
	
	private void checkAndProceedToNextQuestion(UserAnswer userAnswer){
		userAnswers.put(userAnswer.uid,  userAnswer);
		questionScreen.animateProgressView(userAnswer.uid, userAnswer.whatUserGot);
		if(userAnswersStack.containsKey(userAnswer.uid)){
			userAnswersStack.get(userAnswer.uid).add(userAnswer);
		}
		else{
			List<UserAnswer> temp = new ArrayList<UserAnswer>();
			temp.add(userAnswer);
			userAnswersStack.put(userAnswer.uid , temp);
		}
		if(currentUsers.size() == userAnswers.keySet().size() || isChallengeMode()){//every one answered 
			
    		questionScreen.getTimerView().resetTimer();
			for(String u: userAnswers.keySet()){
				if(!isChallengeMode() || u.equalsIgnoreCase(quizApp.getUser().uid))
					questionScreen.animateXpPoints(u, userAnswers.get(u).whatUserGot);
			}
			for(String uid: userAnswers.keySet()){
				questionScreen.highlightOtherUsersOption(uid, userAnswers.get(uid).userAnswer);
			}
			
			new Handler().postDelayed(new Runnable() {
				@Override
				public void run() {
					if(currentQuestions.size()>0){ // more questions
						Question currentQuestion = currentQuestions.remove(0);
						questionScreen.animateQuestionChange(UiText.QUESTION.getValue(quiz.nQuestions - currentQuestions.size()), UiText.GET_READY.getValue(), currentQuestion);
						if(isBotMode())
							scheduleBotAnswer(currentQuestion);
						
				        userAnswers.clear();
					}
					else{
						validateAndShowWinningScreen();
					}
				}
			}, Config.QUESTION_END_DELAY_TIME);
		}
	}
	
	private void scheduleBotAnswer(final Question currentQuestion) {
		new Handler().postDelayed(new Runnable() {
			
			@Override
			public void run() {
					for(User user:currentUsers){ // check for any bots and schedule
						if(!user.isBotUser()) continue;
						
						int elapsedTime = rand.nextInt(5*Math.max(0, (100-quizApp.getUser().getLevel(quiz))/100)); 
						boolean isRightAnswer = rand.nextInt(2)==1? false:true;
						if(isRightAnswer){
							botScore+=Math.ceil((currentQuestion.getTime() - elapsedTime)*currentQuestion.xp/currentQuestion.getTime())*quizApp.getConfig().multiplyFactor(currentQuestions.size());
						}
						final UserAnswer botAnswer = new UserAnswer(currentQuestion.questionId, user.uid, isRightAnswer?currentQuestion.getCorrectAnswer():currentQuestion.getWrongRandomAnswer(rand),
								 	elapsedTime, botScore);
			    		
						new Handler().postDelayed( new Runnable() {
							
							@Override
							public void run() {
								questionScreen.getTimerView().stopPressed(2, botAnswer.elapsedTime);
								checkAndProceedToNextQuestion(botAnswer);
							}
						}, elapsedTime*1000);
					}
			}
		}, Config.PREQUESTION_FADE_OUT_ANIMATION_TIME);

	}

	public List<User> getOtherUsers(){
		ArrayList<User> otherUsers = new ArrayList<User>();
		for(User user : currentUsers){
			if(user.uid != quizApp.getUser().uid)
			otherUsers.add(user);
		}
		return otherUsers;
	}
	
	public void validateAndShowWinningScreen(){
		List<UserAnswer> l = userAnswersStack.get(quizApp.getUser().uid);
		clearScreen();
		
//		ProfileAndChatController profileAndChat = (ProfileAndChatController) quizApp.loadAppController(ProfileAndChatController.class);
//
//		profileAndChat.loadChatScreen(getOtherUsers().get(0), -1, true);
		 
		loadResultScreen(quiz,currentUsers,userAnswersStack);

//		WinOrLoseScreen resultScreen = new WinOrLoseScreen(this,currentUsers);
//		resultScreen.showResult(userAnswersStack,true);
//		showScreen(resultScreen);

	}
	
	public void onMessageRecieved(MessageType messageType, ServerResponse response, String data) {
		switch(messageType){
			case USER_ANSWERED_QUESTION:
	    		UserAnswer userAnswer = quizApp.getConfig().getGson().fromJson(response.payload, UserAnswer.class);
	    		//questionId , self.uid, userAnswer,elapsedTime , whatUserGot
	    		questionScreen.getTimerView().stopPressed(2, userAnswer.elapsedTime);
	    		checkAndProceedToNextQuestion(userAnswer);
	    		break;
	    	case GET_NEXT_QUESTION://client trigger
	    		break; 
	    	case STARTING_QUESTIONS:// start questions // user finalised
	    		noResponseFromServer = false;
	    		currentUsers = quizApp.getConfig().getGson().fromJson(response.payload1,new TypeToken<ArrayList<User>>(){}.getType());
	    		currentQuestions  = quizApp.getConfig().getGson().fromJson(response.payload2,new TypeToken<ArrayList<Question>>(){}.getType());
	    		showQuestionScreen(currentUsers);
	    		break;
	    	case ANNOUNCING_WINNER:
	    		validateAndShowWinningScreen();
	    		break; 
	    	case USER_DISCONNECTED:
	    		if(currentQuestions.size()>0){ // still there are questions ? 
	    			gracefullyCloseSocket();
	    			quizApp.getStaticPopupDialogBoxes().yesOrNo(UiText.USER_HAS_DISCONNECTED.getValue(getOtherUser().name), UiText.CHALLENGE.getValue() , UiText.NO.getValue() , new DataInputListener<Boolean>(){
	    				@Override
	    				public String onData(Boolean s) {
	    					if(s){
	    						//setQuizMode(CHALLENGE_MODE);
	    					}
	    					else{
	    						validateAndShowWinningScreen();
	    					}
	    					return super.onData(s);
	    				}
	    			});
	    		}
	    		else if(waitingForRematch){
	    			waitingForRematch = false;
	    		}
	    		break; 
	    	case NEXT_QUESTION:
	    		break; 
	    	case STATUS_WHAT_USER_GOT:
	    		break; 
	    	case OK_ACTIVATING_BOT: 
	    		quizApp.getServerCalls().informActivatingBot(quiz, serverSocket.serverId); 
	    		setQuizMode(BOT_MODE);
	    		serverSocket.disconnect();
	    		startQuestions(response);
	    		break;
	    	case START_QUESTIONS:
	    		startQuestions(response);
	    		break; 
	    	case REMATCH_REQUEST: 
	    		User user = quizApp.cachedUsers.get(response.payload);
	    		quizApp.getStaticPopupDialogBoxes().yesOrNo(UiText.USER_WANTS_REMATCH.getValue(user.name), UiText.CHALLENGE.getValue() , UiText.EXIT.getValue() , new DataInputListener<Boolean>(){
	    			@Override
	    			public String onData(Boolean s) {
	    				if(s){
	    					serverSocket.sendTextMessage(constructSocketMessage(MessageType.REMATCH_REQUEST, null,null));
	    				}
	    				else{
	    					gracefullyCloseSocket();
	    				}
	    				return super.onData(s);
	    			}
	    		});
	    		break;
	    	case OK_START_REMATCH:
	    		quizApp.getStaticPopupDialogBoxes().removeRematchRequestScreen();
	    		currentQuestions = quizApp.getConfig().getGson().fromJson(response.payload1, new TypeToken<List<Question>>(){}.getType());
	    		showWaitingScreen(quiz);
	    		showQuestionScreen(currentUsers);
	    		break;
	    	case LOAD_CHALLENGE_FROM_OFFLINE:
	    		gracefullyCloseSocket();
	    		loadOfflineChallenge();
	    		break;
//	    	case START_CHALLENGE_NOW:
	    	case OK_CHALLENGE_WITHOUT_OPPONENT:
				setQuizMode(CHALLENGE_MODE);
				startQuestions(response);
				break;

			default:
				break;
		}
	}

	private void loadOfflineChallenge() {
		// fetch data from server and start quiz
	}


	private void startQuestions(ServerResponse response) {
		currentQuestions = quizApp.getConfig().getGson().fromJson(response.payload2, new TypeToken<List<Question>>(){}.getType());
		try{
			currentUsers = quizApp.getConfig().getGson().fromJson(response.payload1, new TypeToken<List<User>>(){}.getType());
		}
		catch(JsonSyntaxException ex){//single user in payload
			currentUsers.clear();
			currentUsers.add(quizApp.getUser());
			currentUsers.add((User) quizApp.getConfig().getGson().fromJson(response.payload1, new TypeToken<User>(){}.getType()));
		}
		quizApp.cacheUsersList(currentUsers);
		showQuestionScreen(currentUsers);
	}

	public User getOtherUser(){
		User otherUser = null;
		for(User user:currentUsers){
			otherUser = user;
			if(!otherUser.uid.equalsIgnoreCase(quizApp.getUser().uid)){
				break;
			}
		}
		return otherUser;
	}
	public void requestRematch(){
		if(serverSocket!=null && serverSocket.isConnected()){
			serverSocket.sendTextMessage(constructSocketMessage(MessageType.REMATCH_REQUEST, null,null));
			waitingForRematch = true;
		}
		else{
			quizApp.getStaticPopupDialogBoxes().yesOrNo(UiText.USER_HAS_DECLINED.getValue(), UiText.CHALLENGE.getValue(), UiText.OK.getValue(), new DataInputListener<Boolean>(){
				@Override
				public String onData(Boolean s) {
					if(s){
						startNewChallenge(getOtherUser());
					}
					else{
						
					}
					return super.onData(s);
				}
			});
		}
	}

	public void onSocketClosed() {
		//TODO: poup
	}
	
	public void gracefullyCloseSocket(){
		if(serverSocket!=null){
			serverSocket.disconnect();
		}
		waitingForRematch = false;
	}


	public void ohNoDammit() {
	}
	
	public void onOptionSelected(Boolean isAnwer, String answer , Question currentQuestion) {
		UserAnswer payload =null; 
		double timeElapsed = questionScreen.getTimerView().stopPressed(1);
		if(isAnwer){
			currentScore += ( Math.ceil(currentQuestion.getTime()-timeElapsed)*quizApp.getConfig().multiplyFactor(currentQuestions.size()));
		}
		payload = new UserAnswer(currentQuestion.questionId, quizApp.getUser().uid, answer, (int)timeElapsed, currentScore);
		if(!isBotMode() || !isChallengeMode())
			serverSocket.sendTextMessage(quizApp.getConfig().getGson().toJson(payload));
		checkAndProceedToNextQuestion(payload);
	}


	public String onNoAnswer(Question currentQuestion) {
		UserAnswer payload =null; 
		currentScore += 0;
		payload = new UserAnswer(currentQuestion.questionId, quizApp.getUser().uid, "", currentQuestion.getTime(), currentScore);//all time elapsed
		if(!isBotMode())
			serverSocket.sendTextMessage(quizApp.getConfig().getGson().toJson(payload));
		questionScreen.highlightCorrectAnswer();
		checkAndProceedToNextQuestion(payload);
		return null;
	}
	
	
	public void setChallengeData(){
		
	}
	
	/**
	 * Main method to load result screen after quiz
	 * @param quiz Current quiz user has played
	 * @param currentUsers Current list of users who played quiz
	 * @param userAnswersStack Current user's answers in hashmap mapped with uid's
	 */
	public void loadResultScreen(Quiz quiz, ArrayList<User> currentUsers, HashMap<String, List<UserAnswer>> userAnswersStack) {
		// TODO Auto-generated method stub
		ArrayList<String> winnersList = whoWon(userAnswersStack);
		int quizResult = 0;
		if(winnersList.contains(this.quizApp.getUser().uid)){
			if(winnersList.size()==1){
				quizResult = 1;
			}
//			else{ // default value
//				// Tie
//			}
		}
		else{
			quizResult = -1;
		}
		if (quizResultScreen==null){
			quizResultScreen = new WinOrLoseScreen(this,currentUsers);
		}
		double cPoints = quizApp.getUser().getPoints(quiz);
		List<UserAnswer> uAns = userAnswersStack.get(quizApp.getUser().uid);
		double newPoints = cPoints+uAns.get(uAns.size()-1).whatUserGot+(quizResult>0?Config.QUIZ_WIN_BONUS:0);
		
		if(quizResult!=-2){
			quiz.userXp+=newPoints;
			quizApp.getDataBaseHelper().createOrUpdateQuiz(quiz);
			
			Integer[] winsLossesQuiz = quizApp.getUser().getWinsLosses(quiz.quizId);
			winsLossesQuiz[0]+= (quizResult==1?1:0);
			winsLossesQuiz[1]+= (quizResult==-1?1:0);
			winsLossesQuiz[2]+= (quizResult==0?1:0);
			
			quizApp.getServerCalls().updateQuizWinStatus(quiz.quizId , quizResult , newPoints);//server call 
			quizApp.getUser().getStats().put(quiz.quizId , (int) quiz.userXp);
		}
		quizResultScreen.showResult(userAnswersStack,quizResult,didUserLevelUp(cPoints,newPoints));
		showScreen(quizResultScreen);
	}

	private ArrayList<String> whoWon(HashMap<String, List<UserAnswer>> userAnswersStack){
		List<UserAnswer> uAns;
		ArrayList<String> winnersList = new ArrayList<String>();
		Set<String> allUsers = userAnswersStack.keySet();
		Iterator<String> itr = allUsers.iterator();
		String uid;
		int maxScore=0;
		while(itr.hasNext()){
			uid = itr.next();
			uAns = userAnswersStack.get(uid);
			if(maxScore<uAns.get(uAns.size()-1).whatUserGot){
				maxScore = uAns.get(uAns.size()-1).whatUserGot;
				winnersList.clear();
				winnersList.add(uid);
			}
			else if(maxScore==uAns.get(uAns.size()-1).whatUserGot){
				winnersList.add(uid);
			}
		}
		return winnersList;
	}
	
	public boolean didUserLevelUp(double oldPoints,double newPoints){
		if (Math.floor(quizApp.getGameUtils().getLevelFromXp(oldPoints))!=
				Math.floor(quizApp.getGameUtils().getLevelFromXp(newPoints))){
			return true;
		}
		return false;
	}

	public void loadProfile(User user) {
		ProfileAndChatController profileAndChat = (ProfileAndChatController) quizApp.loadAppController(ProfileAndChatController.class);
//		profileAndChat.loadChatScreen(user, -1, true);
		profileAndChat.showProfileScreen(user);
	}

	public void startNewChallenge(User otherUser){
		if(otherUser==null) otherUser = getOtherUser();
		//TODO: clear socket , 
		// master server get sid
		// open new socket with &isChallenge=uid2 , get challengeId , etc from server , 
		// else let the user click on start now to Send message START_CHALLENGE_NOW to server , get questions , 
		// and then wait for the opponent to connect ,
		// users , setmode as challenge , complete it to send the offlinechallenge to server 
		gracefullyCloseSocket();
		showChallengeScreen(otherUser);
	}

	public void showChallengeScreen(User otherUser){
			clearScreen();
			clashingScreen = new ClashScreen(this);
			clashingScreen.setClashCount(2); 
			clashingScreen.updateClashScreen(quizApp.getUser()/*quizApp.getUser()*/, 0 , new ChallengeView(quizApp , otherUser, new DataInputListener<Integer>(){
				@Override
				public String onData(Integer s) {
					switch(s){
						case 1://challege start now
							setQuizMode(CHALLENGE_MODE);
							break;
						case 2://exit
							break;
					}
					return super.onData(s);
				}
			}))	;//TODO: change to quizApp.getUser()
			insertScreen(clashingScreen);
			HashMap<String , String> temp = new HashMap<String, String>();
			temp.put("isChallenge", otherUser.uid);
 			quizApp.getServerCalls().startProgressiveQuiz(this, quiz, temp);
	}
	
	public void addFriend(User user) {
		// TODO Auto-generated method stub
	}
}