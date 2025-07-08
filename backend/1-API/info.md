# Backend Overview

This project runs on a **NestJS server** provided by [Hack Club](https://hackclub.com), paired with a **PostgreSQL** database for persistent storage. The server exposes a **GraphQL API** at:

🔗 [https://immercato.hackclub.app/graphql](https://immercato.hackclub.app/graphql)

## Tech Stack

- **Framework**: NestJS
- **Database**: PostgreSQL
- **ORM**: Integrated with an Object-Relational Mapper: Prisma to streamline data interactions
- **API**: GraphQL for flexible querying and mutation operations

## Capabilities

The GraphQL API allows clients to:
- 🔍 Retrieve data
- ➕ Add new records
- 🗑️ Delete entries
- ✏️ Update existing data

All communication flows through the GraphQL layer, with the ORM efficiently managing the connection between the server and database.

## Status Monitoring

If the API endpoint is temporarily unavailable or you're running into issues, it may be undergoing updates or experiencing downtime (e.g. [currently returns HTTP 502 error](https://immercato.hackclub.app/graphql)).

💬 **Reach out on Slack**  
Please DM **@imesh** on the [Hack Club Slack](https://hackclub.slack.com) for support or inquiries.

---

Maintained with care by Imesh 💻