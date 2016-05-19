import time
import json
import requests
import sys
from slackclient import SlackClient
from requests.auth import HTTPBasicAuth
from sqlite3.dbapi2 import TimestampFromTicks

# toneCredentials = {
#     "url": "https://gateway.watsonplatform.net/tone-analyzer-beta/api",
#     "password": "Srdrl738cdDC",
#     "username": "a5dad9b0-da34-4c68-a2f3-d2bb42f23c1c"
#   }

POSITIVE_TONES = ['joy', 'analytical', 'confident', ]
NEGATIVE_TONES = ['anger', 'disgust', 'fear', 'sadness', 'tentative']
INTENSITY = ['openness_big5', 'conscientiousness_big5', 'extraversion_big5', 'agreeableness_big5', 'neuroticism_big5']

EXCLUDE = {'has joined the channel'}

POSITIVE_EMOJI = [':smirk:', ':smile:', ':joy:', ':partyparrot:']
NEGATIVE_EMOJI = [':cry:', ':sob:', ':rage:']
DEFAULT_EMOJI = ':no_mouth:'

user_ids = {}
channelIds = []


def checkExclude(msg):
    for excludeStr in EXCLUDE:
        if excludeStr in msg:
            return True
    return False

def extractToneScores(toneDict):
    toneScores = {}
    if 'document_tone' in toneDict and 'tone_categories' in toneDict['document_tone']:
        categories = toneDict['document_tone']['tone_categories']
        for category in categories:
            # get each category
            if 'tones' in category:
                tones = category['tones']
                for tone in tones:
                    # get score for each tone
                    tone_id = tone['tone_id']
                    score = tone['score']
                    toneScores[tone_id] = score
    return toneScores

def analyzeToneScores(scores):
    # sum up negative ones
    negScore = 0
    for neg in NEGATIVE_TONES:
        negScore = negScore + scores[neg]
    # sum up positive ones
    posScore = 0
    for pos in POSITIVE_TONES:
        posScore = posScore + scores[pos]
    # compare the two
    emoji = POSITIVE_EMOJI
    if negScore > posScore:
        emoji = NEGATIVE_EMOJI
    # compute intensity
    intenseScore = 0
    for intense in INTENSITY:
        intenseScore = intenseScore + scores[intense]
    print(negScore, posScore, intenseScore)
    if intenseScore > 3:
        return emoji[2]
    if intenseScore > 2.5:
        return emoji[1]
    return emoji[0]

# analyze the tone of the message
def analyzeTone(toneCredentials, msg):
    response = requests.get(toneCredentials['url'] + "/v3/tone",
                            auth = HTTPBasicAuth(toneCredentials['username'], toneCredentials['password']),
                            headers = {'Content-type': 'application/json'},
                            params = {'text':msg, 'version':'2016-02-11'})
    if response.status_code == 200:
        # everything went smoothly
        print "TONE ANALYZER"
        #print response.text
        return extractToneScores(json.loads(response.text))
    else:
        print "status code " + str(response.status_code)
        print response.text
        return []

def processMessage(client, user, message, channel, timestamp, toneCredentials):
    if (not checkExclude(message)) :
#         if user != BOT_USER:
#             # client.api_call("chat.postMessage", as_user="true", channel=channel, text="well said!")
        scores = analyzeTone(toneCredentials, message)
        emoji = DEFAULT_EMOJI
        if scores != []:
            emoji = analyzeToneScores(scores)
            #client.api_call("chat.postMessage", as_user="true", channel=channel, text="I feel for you " + emoji)
            client.api_call("reactions.add", channel = channel, name = emoji.strip(':'), timestamp = timestamp )

def readChannelHistory(channel, timestamp, now, toneCredentials):
    # read messages from this channel since the timestamp until now
    hasMore = True
    while hasMore:
        response = sc.api_call("channels.history", channel = channel, oldest = timestamp, latest = now, count = 1000)
        hasMore = response['has_more']
        messages = response['messages']
        for line in messages:
            if 'text' in line:
                message = line['text']
                user = line['user']
                timestamp = line['ts']
                processMessage(user, message, channel, timestamp)

def main(params):
    if not isinstance(params, dict):
        return {'error': 'parameter passed to function main is not a dictionary'}
    if 'bot' not in params:
        return {'error': 'bot not in the parameters dictionary'}
    if 'token' not in params:
        return {'error': 'token not in the parameters dictionary'}
    if 'channel' not in params:
        return {'error': 'channel to analyze is not in the parameters dictionary'}
    if 'bluemixCredentials' not in params:
        return {'error': 'bluemixCredentials are not in the parameters dictionary'}
    if 'reps' not in parmas:
        return {'error': 'reps - the number of repetitions - is not in the parameters dictionary'}
    if 'continue' not in parmas:
        return {'error': 'continue - the name of the continuation trigger - is not in the parameters dictionary'}
    timestamp = None
    if 'timestamp' in params:
        timestamp = params['timestamp']
    bot = params['bot']
    token = params['token']
    channel = params['channel']
    toneCredentials = params['bluemixCredentials']
    triggerName = params['continue']
    reps = params['reps']
    sc = SlackClient(token)
    now = time.time()
    if sc.rtm_connect():
        if timestamp != None:
            # if there is a timestamp read history and react to it
            process_history(channel, timestamp, now, toneCredentials)
        # keep on monitoring channel until no event is found
        while True:
            event = sc.rtm_read()
            if not event == []:
                # print(event)
                for line in event:
                    print 'LINE'
                    print line
                    if 'text' not in line or 'channel' not in line or 'user' not in line:
                        continue
                    if channel == line['channel']:
                        print 'MESSAGE'
                        message = line['text']
                        print message
                        user = line['user']
                        timestamp = line['ts']
                        processMessage(sc, user, message, channel, timestamp)
            else:
                if reps == 0:
                    return {'timestamp': timestamp}
                # fire the cron trigger and return
                apiBaseUrl='https://openwhisk.ng.bluemix.net/api/v1'
                path = '/namespaces/{namespace}/triggers/{triggerName}'
                # Substitute variables
                path.replace(triggerName,"{triggerName}")
                path.replace(namespace,"_") # default namespace
                # call the trigger with the timestamp and the number of repetitions
                return {'timestamp': timestamp}

main({'bot': 'U14UXSBC6', 'token': 'xoxb-40830491312-Ctke28HiiE3PuhjYGYAA7dwW', 'channel': 'C08V51YM6', "bluemixCredentials": {
    'url': 'https://gateway.watsonplatform.net/tone-analyzer-beta/api',
    'password': 'Srdrl738cdDC',
    'username': 'a5dad9b0-da34-4c68-a2f3-d2bb42f23c1c'
  }, 'continue': 'every30s', 'reps': 5})

